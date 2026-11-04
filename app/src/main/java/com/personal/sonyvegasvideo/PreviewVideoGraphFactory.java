package com.personal.sonyvegasvideo;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.effect.SingleInputVideoGraph;
import java.util.List;
import java.util.concurrent.Executor;

/** Preserves decoded frames on older codec surfaces and in layered previews. */
final class PreviewVideoGraphFactory implements VideoGraph.Factory {
    private final VideoGraph.Factory delegate;

    PreviewVideoGraphFactory(boolean multipleInputs){
        delegate=multipleInputs?new MultipleInputVideoGraph.Factory():new SingleInputVideoGraph.Factory();
    }

    @Override public boolean supportsMultipleInputs(){return delegate.supportsMultipleInputs();}

    @Override public VideoGraph create(Context context,ColorInfo outputColorInfo,DebugViewProvider debugViewProvider,
            VideoGraph.Listener listener,Executor listenerExecutor,long initialTimestampOffsetUs,boolean renderFramesAutomatically){
        VideoGraph graph=delegate.create(context,outputColorInfo,debugViewProvider,listener,listenerExecutor,initialTimestampOffsetUs,renderFramesAutomatically);
        return new VideoGraph(){
            @Override public void initialize()throws VideoFrameProcessingException {graph.initialize();}
            @Override public void registerInput(int input)throws VideoFrameProcessingException {graph.registerInput(input);}
            @Override public void setOutputSurfaceInfo(SurfaceInfo info){graph.setOutputSurfaceInfo(info);}
            @Override public void setOnInputSurfaceReadyListener(int input,Runnable callback){graph.setOnInputSurfaceReadyListener(input,callback);}
            @Override public Surface getInputSurface(int input){return graph.getInputSurface(input);}
            @Override public void setOnInputFrameProcessedListener(int input,OnInputFrameProcessedListener callback){graph.setOnInputFrameProcessedListener(input,callback);}
            @Override public void setCompositionEffects(List<Effect> effects){graph.setCompositionEffects(effects);}
            @Override public void setCompositorSettings(VideoCompositorSettings settings){graph.setCompositorSettings(settings);}
            @Override public void registerInputStream(int input,int type,Format format,List<Effect> effects,long offsetUs){graph.registerInputStream(input,type,format,effects,offsetUs);}
            @Override public int getPendingInputFrameCount(int input){return graph.getPendingInputFrameCount(input);}
            @Override public boolean registerInputFrame(int input){
                // Media3 Util.getMaxPendingFramesCountForMediaCodecDecoders uses
                // this same one-frame limit where SurfaceTexture may drop frames,
                // including devices that ignore KEY_ALLOW_FRAME_DROP=0. Keep
                // decoder output under backpressure until its texture is consumed.
                return graph.getPendingInputFrameCount(input)<1&&graph.registerInputFrame(input);
            }
            @Override public boolean queueInputBitmap(int input,Bitmap bitmap,TimestampIterator times){return graph.queueInputBitmap(input,bitmap,times);}
            @Override public boolean queueInputTexture(int input,int texture,long timeUs){return graph.queueInputTexture(input,texture,timeUs);}
            @Override public void renderOutputFrame(long timeNs){graph.renderOutputFrame(timeNs);}
            @Override public void redraw(){graph.redraw();}
            @Override public boolean hasProducedFrameWithTimestampZero(){return graph.hasProducedFrameWithTimestampZero();}
            @Override public void flush(){graph.flush();}
            @Override public void signalEndOfInput(int input){graph.signalEndOfInput(input);}
            @Override public void release(){graph.release();}
        };
    }
}
