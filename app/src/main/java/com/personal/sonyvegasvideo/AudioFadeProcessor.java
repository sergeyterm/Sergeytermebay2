package com.personal.sonyvegasvideo;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Sample-accurate linear fades on a clipped event, before the composition mixes lanes. */
final class AudioFadeProcessor extends BaseAudioProcessor {
    private final long durationUs,fadeInUs,fadeOutUs,projectStartUs;
    private volatile long nextFlushPositionUs;
    private long frame;
    private int channel;
    AudioFadeProcessor(long durationUs,long fadeInUs,long fadeOutUs,long projectStartUs){
        this.durationUs=Math.max(1,durationUs);
        this.fadeInUs=Math.max(0,Math.min(durationUs,fadeInUs));
        this.fadeOutUs=Math.max(0,Math.min(durationUs,fadeOutUs));
        this.projectStartUs=Math.max(0,projectStartUs);
    }
    void seekProjectPosition(long projectUs){nextFlushPositionUs=Math.max(0,Math.min(durationUs,projectUs-projectStartUs));}
    @Override protected AudioFormat onConfigure(AudioFormat input)throws UnhandledAudioFormatException {
        if(input.encoding!=C.ENCODING_PCM_16BIT)throw new UnhandledAudioFormatException(input);
        return fadeInUs>0||fadeOutUs>0?input:AudioFormat.NOT_SET;
    }
    @Override protected void onFlush(){
        frame=nextFlushPositionUs*Math.max(1,inputAudioFormat.sampleRate)/1_000_000L;channel=0;
    }
    @Override protected void onReset(){nextFlushPositionUs=0;frame=0;channel=0;}
    @Override public void queueInput(ByteBuffer input){
        ByteBuffer output=replaceOutputBuffer(input.remaining()).order(ByteOrder.nativeOrder());
        input.order(ByteOrder.nativeOrder());int channels=Math.max(1,inputAudioFormat.channelCount),rate=Math.max(1,inputAudioFormat.sampleRate);
        while(input.remaining()>=2){
            long timeUs=frame*1_000_000L/rate;double gain=1;
            if(fadeInUs>0)gain=Math.min(gain,timeUs/(double)fadeInUs);
            if(fadeOutUs>0)gain=Math.min(gain,(durationUs-timeUs)/(double)fadeOutUs);
            gain=Math.max(0,Math.min(1,gain));int value=(int)Math.round(input.getShort()*gain);
            output.putShort((short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,value)));
            if(++channel==channels){channel=0;frame++;}
        }
        while(input.hasRemaining())output.put(input.get());output.flip();
    }
}
