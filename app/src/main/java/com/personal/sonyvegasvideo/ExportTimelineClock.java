package com.personal.sonyvegasvideo;

import java.util.HashSet;
import java.util.Set;

/** Preserves the project clock when a codec supplies its first picture after time zero. */
final class ExportTimelineClock {
    private final Set<Integer> videoTracks=new HashSet<>(),startedVideoTracks=new HashSet<>();
    void videoTrack(int trackId){videoTracks.add(trackId);}
    long presentationTimeUs(int trackId,long timestampUs,int bytes,boolean keyFrame,boolean endOfStream){
        if(!videoTracks.contains(trackId)||bytes<=0||endOfStream||startedVideoTracks.contains(trackId))return timestampUs;
        if(!keyFrame)throw new IllegalArgumentException("Кодировщик не создал ключевой кадр для начала видео.");
        startedVideoTracks.add(trackId);
        // Do not shift later pictures or any audio samples. Holding this first keyframe
        // across the initial decoder gap keeps transitions and A/V on the project clock.
        return timestampUs>0?0:timestampUs;
    }
}
