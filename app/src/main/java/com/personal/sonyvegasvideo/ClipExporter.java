package com.personal.sonyvegasvideo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Copies complete video GOPs and the first audio track without decoding or re-encoding. */
public final class ClipExporter {
    public interface Progress { void update(int percent); }
    public static final class Result {
        public final Uri uri;
        public final long startUs;
        public final long endUs;
        Result(Uri uri, long startUs, long endUs) {
            this.uri = uri; this.startUs = startUs; this.endUs = endUs;
        }
    }
    private static final int MAX_SAMPLE_BYTES = 64 * 1024 * 1024;
    private ClipExporter() { }

    public static Result export(Context context, Uri source, long startUs, long endUs,
                                boolean mute, AtomicBoolean cancelled, Progress progress) throws IOException {
        if (!ClipMath.validRange(startUs, endUs)) throw new IOException("Некорректные границы фрагмента.");
        ContentResolver resolver = context.getContentResolver();
        MediaExtractor extractor = new MediaExtractor();
        Uri destination = null;
        boolean completed = false;
        try {
            checkCancelled(cancelled);
            extractor.setDataSource(context, source, null);
            int video = -1, audio = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (video < 0 && mime.startsWith("video/")) video = i;
                if (audio < 0 && mime.startsWith("audio/")) audio = i;
            }
            if (video < 0) throw new IOException("В файле нет видеодорожки.");
            MediaFormat videoFormat = extractor.getTrackFormat(video);
            extractor.selectTrack(video);
            // Start on a random-access frame; include the final complete GOP so B-frames
            // never lose their future reference pictures at the requested end boundary.
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long actualStart = extractor.getSampleTime();
            if (actualStart < 0 || actualStart >= endUs
                    || (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                throw new IOException("Не найден ключевой кадр для начала фрагмента.");
            }
            extractor.seekTo(endUs, MediaExtractor.SEEK_TO_NEXT_SYNC);
            long nextSync = extractor.getSampleTime();
            long duration = videoFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? videoFormat.getLong(MediaFormat.KEY_DURATION) : endUs;
            // Some extractors clamp NEXT_SYNC to the last sync frame at EOF.
            long actualEnd = nextSync >= endUs ? nextSync : Math.max(endUs, duration);
            if (actualEnd <= actualStart) throw new IOException("Фрагмент пуст.");
            extractor.seekTo(actualStart, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            if (!mute && audio >= 0) extractor.selectTrack(audio);
            checkCancelled(cancelled);

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "VegasVideo_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Sony Vegas Video");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            destination = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("Не удалось создать видео в Галерее.");
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(destination, "rw")) {
                if (descriptor == null) throw new IOException("Не удалось открыть выходной файл.");
                MediaMuxer muxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                try {
                    int videoOut = muxer.addTrack(videoFormat);
                    int audioOut = !mute && audio >= 0 ? muxer.addTrack(extractor.getTrackFormat(audio)) : -1;
                    int rotation = videoFormat.containsKey(MediaFormat.KEY_ROTATION)
                            ? videoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
                    if (rotation == 90 || rotation == 180 || rotation == 270) muxer.setOrientationHint(rotation);
                    muxer.start();
                    ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int writtenVideo = 0, lastPercent = -1;
                    while (extractor.getSampleTrackIndex() >= 0) {
                        checkCancelled(cancelled);
                        int track = extractor.getSampleTrackIndex();
                        long time = extractor.getSampleTime();
                        if (time >= actualEnd) {
                            // End each track independently: audio/video samples are interleaved.
                            extractor.unselectTrack(track);
                            continue;
                        }
                        if (time >= actualStart) {
                            int flags = extractor.getSampleFlags();
                            if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                                throw new IOException("Защищённое видео не поддерживается.");
                            if ((flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0)
                                throw new IOException("Видео содержит неполные кадры. Выберите другой файл.");
                            long sampleSize = extractor.getSampleSize();
                            if (sampleSize < 0) break;
                            if (sampleSize > MAX_SAMPLE_BYTES)
                                throw new IOException("Размер одного кадра превышает 64 МБ.");
                            if (sampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) sampleSize);
                            buffer.clear();
                            int size = extractor.readSampleData(buffer, 0);
                            if (size < 0) break;
                            // Extractor and codec flags have different meanings for bits 2 and 4.
                            int codecFlags = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            info.set(0, size, time - actualStart, codecFlags);
                            muxer.writeSampleData(track == video ? videoOut : audioOut, buffer, info);
                            if (track == video) writtenVideo++;
                            int percent = ClipMath.progress(time, actualStart, actualEnd);
                            if (percent > lastPercent) { progress.update(percent); lastPercent = percent; }
                        }
                        if (!extractor.advance()) break;
                    }
                    if (writtenVideo == 0) throw new IOException("В выбранном фрагменте нет видеокадров.");
                    checkCancelled(cancelled);
                    muxer.stop();
                } finally {
                    // Also release when stop() or writeSampleData() throws.
                    muxer.release();
                }
            }
            checkCancelled(cancelled);
            ContentValues published = new ContentValues();
            published.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (resolver.update(destination, published, null, null) != 1)
                throw new IOException("Не удалось опубликовать видео в Галерее.");
            completed = true;
            progress.update(100);
            return new Result(destination, actualStart, actualEnd);
        } finally {
            try { extractor.release(); }
            finally {
                if (!completed && destination != null) resolver.delete(destination, null, null);
            }
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
