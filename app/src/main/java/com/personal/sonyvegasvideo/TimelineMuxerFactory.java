package com.personal.sonyvegasvideo;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.transformer.DefaultMuxer;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;

/** Anchors the first picture and declares the intended final frame duration to the MP4 muxer. */
final class TimelineMuxerFactory implements Muxer.Factory {
    private final DefaultMuxer.Factory delegate;
    TimelineMuxerFactory(long durationUs){
        if(durationUs<=0)throw new IllegalArgumentException("Некорректная длительность проекта.");
        delegate=new DefaultMuxer.Factory().setVideoDurationUs(durationUs);
    }
    @Override public Muxer create(String path)throws MuxerException {
        Muxer muxer=delegate.create(path);ExportTimelineClock clock=new ExportTimelineClock();
        return new Muxer(){
            @Override public int addTrack(Format format)throws MuxerException {
                int track=muxer.addTrack(format);if(MimeTypes.isVideo(format.sampleMimeType))clock.videoTrack(track);return track;
            }
            @Override public void writeSampleData(int track,ByteBuffer data,BufferInfo info)throws MuxerException {
                final long time;
                try{time=clock.presentationTimeUs(track,info.presentationTimeUs,info.size,(info.flags&C.BUFFER_FLAG_KEY_FRAME)!=0,(info.flags&C.BUFFER_FLAG_END_OF_STREAM)!=0);}
                catch(IllegalArgumentException invalid){throw new MuxerException(invalid.getMessage(),invalid);}
                muxer.writeSampleData(track,data,time==info.presentationTimeUs?info:new BufferInfo(time,info.size,info.flags));
            }
            @Override public void addMetadataEntry(Metadata.Entry entry){muxer.addMetadataEntry(entry);}
            @Override public void close()throws MuxerException {muxer.close();}
        };
    }
    @Override public ImmutableList<String> getSupportedSampleMimeTypes(int trackType){return delegate.getSupportedSampleMimeTypes(trackType);}
    @Override public boolean supportsWritingNegativeTimestampsInEditList(){return delegate.supportsWritingNegativeTimestampsInEditList();}
}
