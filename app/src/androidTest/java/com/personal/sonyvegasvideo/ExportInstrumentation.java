package com.personal.sonyvegasvideo;

import android.app.Instrumentation;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Device integration tests without external libraries. Run with am instrument -w. */
public final class ExportInstrumentation extends Instrumentation {
    private Bundle options;
    @Override public void onCreate(Bundle args) { super.onCreate(args); options=args; start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        if(options!=null&&"ui".equals(options.getString("mode"))){
            try{WorkspaceSmoke.run(this,options.getString("screenshot","wide"));report.putString("stream","\nPASS: native workspace, media import, loop handles, undo/redo, mute, transport, screenshot\n");finish(-1,report);}
            catch(Throwable error){report.putString("stream","\nFAIL: "+android.util.Log.getStackTraceString(error));finish(0,report);}return;
        }
        File source = new File(getTargetContext().getCacheDir(), "export-test.mp4");
        try {
            try (InputStream in = getContext().getAssets().open("rotated-av.mp4");
                 OutputStream out = new FileOutputStream(source)) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = in.read(buffer)) >= 0) out.write(buffer, 0, size);
            }
            Context context = getTargetContext();
            Uri input = Uri.fromFile(source);
            checkExport(context, input, false);
            checkExport(context, input, true);
            checkSequence(context,input,false);
            checkSequence(context,input,true);
            // Cancellation after writing has begun must remove the pending MediaStore item.
            java.util.Set<String> before = outputs(context);
            AtomicBoolean cancel = new AtomicBoolean();
            try {
                ClipExporter.export(context, input, 0, 6_000_000, false, cancel,
                        value -> { if (value > 5) cancel.set(true); });
                throw new AssertionError("Cancellation was ignored");
            } catch (CancellationException expected) { }
            require(outputs(context).equals(before), "Cancelled export left a MediaStore row");
            report.putString("stream", "\nPASS: A/V trim with B-frames, mute, orientation, keyframe bounds, sequence assembly/reordering, cancellation cleanup\n");
            finish(-1, report);
        } catch (Throwable error) {
            report.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error));
            finish(0, report);
        } finally { source.delete(); }
    }

    private void checkExport(Context context, Uri source, boolean mute) throws Exception {
        ClipExporter.Result result = ClipExporter.export(context, source, 1_300_000, 2_700_000,
                mute, new AtomicBoolean(), value -> { });
        try {
            require(result.startUs == 1_000_000, "Start is not the previous sync frame: " + result.startUs);
            require(result.endUs == 3_000_000, "End is not the next sync frame: " + result.endUs);
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, result.uri, null);
                int video = -1, audioCount = 0;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video/")) video = i;
                    if (mime.startsWith("audio/")) audioCount++;
                }
                require(video >= 0, "Missing video track");
                require(audioCount == (mute ? 0 : 1), "Incorrect audio track count");
                extractor.selectTrack(video);
                require((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0, "First video sample is not sync");
                int frames = 0;
                while (extractor.getSampleTrackIndex() >= 0) { frames++; if (!extractor.advance()) break; }
                require(frames == 48, "Incomplete GOP or missing B-frames: " + frames);
            } finally { extractor.release(); }
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(context, result.uri);
                require("90".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)), "Lost rotation");
                long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                require(duration >= 1900 && duration <= 2100, "Unexpected duration: " + duration);
            } finally { metadata.release(); }
        } finally { context.getContentResolver().delete(result.uri, null, null); }
    }

    private void checkSequence(Context context,Uri input,boolean muted)throws Exception{
        Sequence source=new Sequence().append(new Sequence.Clip(input.toString(),"first",0,2000))
                .append(new Sequence.Clip(input.toString(),"last",4000,6000)).move(1,0);
        ClipExporter.Result result=SequenceExporter.export(context,source,muted,new AtomicBoolean(),value->{});
        try{
            require(result.endUs==4_000_000,"Wrong sequence duration");
            MediaExtractor extractor=new MediaExtractor();
            try{
                extractor.setDataSource(context,result.uri,null);int video=-1,audio=0;
                for(int i=0;i<extractor.getTrackCount();i++){String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime.startsWith("video/"))video=i;else if(mime.startsWith("audio/"))audio++;}
                require(video>=0&&audio==(muted?0:1),"Wrong sequence tracks");extractor.selectTrack(video);
                java.nio.ByteBuffer first=java.nio.ByteBuffer.allocate(1024*1024);int bytes=extractor.readSampleData(first,0);byte[] outputFirst=new byte[bytes];first.position(0);first.get(outputFirst);
                MediaExtractor original=new MediaExtractor();try{
                    original.setDataSource(context,input,null);for(int i=0;i<original.getTrackCount();i++){String mime=original.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime.startsWith("video/")){original.selectTrack(i);break;}}
                    original.seekTo(4_000_000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);first.clear();int expected=original.readSampleData(first,0);byte[] sourceFirst=new byte[expected];first.position(0);first.get(sourceFirst);
                    require(java.util.Arrays.equals(outputFirst,sourceFirst),"Reordered export starts on wrong source frame");
                }finally{original.release();}
                int frames=0;while(extractor.getSampleTrackIndex()>=0){frames++;if(!extractor.advance())break;}require(frames==96,"Missing or duplicated sequence frames: "+frames);
            }finally{extractor.release();}
        }finally{context.getContentResolver().delete(result.uri,null,null);}
    }

    private java.util.Set<String> outputs(Context context) {
        java.util.Set<String> rows = new java.util.HashSet<>();
        Bundle args = new Bundle();
        args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ?");
        args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{"Movies/Sony Vegas Video/"});
        Uri collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            args.putInt(android.provider.MediaStore.QUERY_ARG_MATCH_PENDING, android.provider.MediaStore.MATCH_INCLUDE);
        } else {
            collection = android.provider.MediaStore.setIncludePending(collection);
        }
        try (android.database.Cursor cursor = context.getContentResolver().query(
                collection, new String[]{"_id"}, args, null)) {
            if (cursor != null) while (cursor.moveToNext()) rows.add(cursor.getString(0));
        }
        return rows;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
