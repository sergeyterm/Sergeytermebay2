package com.personal.sonyvegasvideo;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;

/** Checks decoded output content, not only Transformer completion callbacks. */
final class EditedExportChecks {
    private final NativeTestSupport s;
    private final ExportJob job=ExportJob.get();
    private String phase="initialization";
    private int checks;
    private EditedExportChecks(NativeTestSupport s){this.s=s;}
    static String run(Instrumentation test)throws Exception{
        try(NativeTestSupport support=new NativeTestSupport(test)){
            EditedExportChecks suite=new EditedExportChecks(support);
            try{suite.execute();return "edited export: "+suite.checks+" content checks; real Media3 effects, four transitions, title, animation, generators, PIP, audio gain/music, mixed sources, exact trim, cancellation/retry";}
            catch(Throwable failure){throw new AssertionError("Edited export phase: "+suite.phase,failure);}
            finally{support.main(()->{suite.job.cancel();return null;});support.await(()->!support.main(()->suite.job.running),15000,"Export did not stop after test");}
        }
    }
    private void execute()throws Exception{
        Uri source=s.fixture("rotated-av.mp4"),red=s.fixture("color-red.mp4"),blue=s.fixture("color-blue.mp4"),green=s.fixture("chroma-green.mp4"),different=s.fixture("different-av.mp4");
        step("exact non-keyframe trim and playable baseline");
        Uri baseline=render(one(source,137,1137),new EffectSpec(),false);
        check(duration(baseline)>=950&&duration(baseline)<=1100,"Exact trim duration drift: "+duration(baseline));
        check(audioTracks(baseline)==1,"Baseline lost audio");
        double baselineRms=rms(baseline);check(baselineRms>.005,"Baseline audio is silent: "+baselineRms);
        Bitmap baselinePattern=frame(baseline,500_000);check(baselinePattern.getWidth()>10,"Baseline cannot decode");

        step("brightness and monochrome filter change encoded pixels");
        EffectSpec brighter=new EffectSpec();brighter.brightness=.3f;
        Uri bright=render(one(blue,0,1000),brighter,true);
        Bitmap brightFrame=frame(bright,500_000);check(mean(brightFrame,0)>.15&&mean(brightFrame,1)>.15,"Brightness was not encoded");brightFrame.recycle();
        EffectSpec monochrome=new EffectSpec();monochrome.preset="mono";
        Uri mono=render(one(red,0,1000),monochrome,true);Bitmap monoFrame=frame(mono,500_000);
        double[] monoColor=rgb(monoFrame,.5,.5);check(Math.max(monoColor[0],Math.max(monoColor[1],monoColor[2]))-Math.min(monoColor[0],Math.min(monoColor[1],monoColor[2]))<.1,"Monochrome output remains colored");monoFrame.recycle();

        step("blur and mosaic alter spatial detail");
        EffectSpec smooth=new EffectSpec();smooth.blur=12;smooth.mosaic=70;
        Uri smoothed=render(one(source,137,1137),smooth,true);Bitmap smoothFrame=frame(smoothed,500_000);
        check(edgeEnergy(smoothFrame)<edgeEnergy(baselinePattern)*.9,"Blur/mosaic did not reduce fine detail: "+edgeEnergy(smoothFrame)+" vs "+edgeEnergy(baselinePattern));smoothFrame.recycle();baselinePattern.recycle();

        step("chroma key removes green and preserves red subject");
        EffectSpec key=new EffectSpec();key.chromaKey=true;key.chromaThreshold=.3f;
        Uri keyed=render(one(green,0,1000),key,true);Bitmap keyFrame=frame(keyed,500_000);
        double[] corner=rgb(keyFrame,.08,.08),center=rgb(keyFrame,.5,.5);
        check(corner[1]<.2&&corner[0]<.2&&corner[2]<.2,"Green background was not removed");
        check(center[0]>.6&&center[1]<.35,"Chroma key removed the red subject");saveFrame(keyFrame,"effect-chroma.png");keyFrame.recycle();

        for(String transition:new String[]{"dissolve","cross","wipe","cascade"}){
            step("transition "+transition+" changes first/last frames");EffectSpec effect=new EffectSpec();effect.transition=transition;effect.transitionMs=300;
            Uri out=render(one(red,0,1200),effect,true);Bitmap first=frame(out,0),middle=frame(out,600_000),last=frame(out,1_160_000);
            double mid=mean(middle,0);check(mid>.6,"Transition obscures middle of clip: "+transition);
            check(mean(first,0)<mid*.8,"Transition has no entry animation: "+transition);
            check(mean(last,0)<mid*.85,"Transition has no exit animation: "+transition);first.recycle();middle.recycle();last.recycle();
        }

        step("text overlay is visible in encoded output");EffectSpec title=new EffectSpec();title.title="VEGAS QA";title.titleSize=.18f;title.titleY=0;
        Uri titled=render(one(red,0,1000),title,true);Bitmap titleFrame=frame(titled,500_000);check(whiteFraction(titleFrame)>.002,"Text overlay missing from encoded video");saveFrame(titleFrame,"effect-title.png");titleFrame.recycle();

        step("zoom keyframes animate encoded frame coverage");EffectSpec motion=new EffectSpec();motion.zoomStart=.5f;motion.zoomEnd=1.5f;
        Uri animated=render(one(red,0,1000),motion,true);Bitmap early=frame(animated,80_000),late=frame(animated,850_000);
        check(mean(late,0)>mean(early,0)*1.3,"Zoom start/end parameters did not animate");early.recycle();late.recycle();

        step("PIP composites separate sources");EffectSpec pip=new EffectSpec();pip.overlayUri=red.toString();pip.overlayScale=.5f;pip.overlayX=0;pip.overlayY=0;
        Uri composite=render(one(blue,0,1000),pip,true);Bitmap pipFrame=frame(composite,500_000);
        double[] pipCenter=rgb(pipFrame,.5,.5),pipCorner=rgb(pipFrame,.05,.05);
        check(pipCenter[0]>.6&&pipCenter[2]<.4,"PIP red layer missing at requested position");
        check(pipCorner[2]>.6&&pipCorner[0]<.4,"PIP replaced its blue background");saveFrame(pipFrame,"effect-composite.png");pipFrame.recycle();

        step("master gain, mute and independent music track");EffectSpec gain=new EffectSpec();gain.masterGain=.2f;
        Uri quiet=render(one(source,137,1137),gain,false);double quietRms=rms(quiet);double ratio=quietRms/baselineRms;
        check(ratio>.06&&ratio<.45,"Master gain not reflected in AAC: ratio="+ratio);
        Uri muted=render(one(source,137,1137),new EffectSpec(),true);check(audioTracks(muted)==0,"Muted export has a source audio track");
        EffectSpec music=new EffectSpec();music.audioUri=source.toString();music.audioGain=.7f;
        Uri musicOut=render(one(red,0,1000),music,true);check(audioTracks(musicOut)==1&&rms(musicOut)>.005,"Added music was not encoded");

        step("mixed resolution/rotation sequence normalizes and decodes");
        Sequence mixed=one(source,137,937).append(new Sequence.Clip(different.toString(),"portrait",100,900));Uri mixedOut=render(mixed,new EffectSpec(),false);
        check(duration(mixedOut)>=1500&&duration(mixedOut)<=1750,"Mixed source duration drift: "+duration(mixedOut));check(audioTracks(mixedOut)==1,"Mixed sources lost audio");
        Bitmap a=frame(mixedOut,300_000),b=frame(mixedOut,1_200_000);check(a.getWidth()==b.getWidth()&&a.getHeight()==b.getHeight(),"Mixed sources changed output geometry");a.recycle();b.recycle();

        step("all generator PNGs and image-to-MP4 export");
        for(String kind:new String[]{"solid","text","checker","gradient"}){
            Uri png=GeneratedMedia.create(s.context,kind,"VEGAS QA",Color.BLUE,320,180);Bitmap image;
            try(InputStream stream=s.context.getContentResolver().openInputStream(png)){image=BitmapFactory.decodeStream(stream);}
            check(image!=null&&image.getWidth()==320&&image.getHeight()==180,"Invalid generated image "+kind);
            if(kind.equals("solid"))check(Color.blue(image.getPixel(100,100))>180,"Solid color ignored");else check(distinctPixels(image)>1,"Generator has no visible content: "+kind);
            image.recycle();
            if(kind.equals("gradient")){
                s.main(()->{job.startGenerated(s.context,png,1000);return null;});Uri video=finishJob();check(duration(video)>=950&&duration(video)<=1100,"Generator video duration wrong");Bitmap generatedFrame=frame(video,500_000);check(distinctPixels(generatedFrame)>3,"Generated MP4 lost gradient");generatedFrame.recycle();
            }
        }

        step("cancel removes pending output and permits retry");
        Set<String> before=s.movieRows();EffectSpec heavy=new EffectSpec();heavy.blur=20;heavy.title="Cancellation check";
        s.main(()->{job.startEdited(s.context,one(source,0,6000),heavy,false);job.cancel();return null;});
        s.await(()->!s.main(()->job.running),30000,"Cancellation did not finish");check(s.main(()->job.result)==null,"Cancelled export published output");check(s.movieRows().equals(before),"Cancellation leaked a MediaStore row");
        step("invalid input reports error; subsequent render works");
        s.main(()->{job.startEdited(s.context,one(Uri.parse("content://com.personal.sonyvegasvideo.generated/missing-qa.mp4"),0,1000),new EffectSpec(),false);return null;});
        s.await(()->!s.main(()->job.running),30000,"Invalid source left job running");check(s.main(()->job.result)==null&&!s.main(()->job.message).isEmpty(),"Invalid source did not report a usable error");
        Uri retried=render(one(red,0,800),new EffectSpec(),true);check(duration(retried)>=750,"Retry failed after an invalid source");
    }
    private void step(String value){phase=value;s.step(value);}
    private void check(boolean condition,String message){checks++;NativeTestSupport.require(condition,message);}
    private static Sequence one(Uri source,int start,int end){return new Sequence().append(new Sequence.Clip(source.toString(),"QA clip",start,end));}
    private Uri render(Sequence clips,EffectSpec effects,boolean muted){s.main(()->{NativeTestSupport.require(!job.running,"Previous export still running");job.startEdited(s.context,clips,effects,muted);return null;});return finishJob();}
    private Uri finishJob(){
        s.await(()->!s.main(()->job.running),120000,"Export timed out: "+phase+"; "+s.main(()->job.message));
        ClipExporter.Result result=s.main(()->job.result);check(result!=null,"Export failed: "+s.main(()->job.message));s.own(result.uri);return result.uri;
    }
    private long duration(Uri uri)throws Exception{MediaMetadataRetriever reader=new MediaMetadataRetriever();try{reader.setDataSource(s.context,uri);return Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}finally{reader.release();}}
    private Bitmap frame(Uri uri,long at)throws Exception{MediaMetadataRetriever reader=new MediaMetadataRetriever();try{reader.setDataSource(s.context,uri);Bitmap frame=reader.getScaledFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST,200,200);check(frame!=null,"Cannot decode edited video at "+at);return frame;}finally{reader.release();}}
    private void saveFrame(Bitmap bitmap,String name)throws Exception{try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(s.context.getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}}
    private int audioTracks(Uri uri)throws Exception{MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(s.context,uri,null);int count=0,video=0;for(int i=0;i<extractor.getTrackCount();i++){String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime.startsWith("audio/"))count++;else if(mime.startsWith("video/"))video++;}check(video==1,"Export does not contain one video track");return count;}finally{extractor.release();}}
    private double rms(Uri uri)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaCodec decoder=null;
        try{
            extractor.setDataSource(s.context,uri,null);int audio=-1;for(int i=0;i<extractor.getTrackCount();i++){String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime.startsWith("audio/")){audio=i;break;}}
            check(audio>=0,"Missing audio to measure");extractor.selectTrack(audio);MediaFormat format=extractor.getTrackFormat(audio);decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));decoder.configure(format,null,null,0);decoder.start();
            boolean inputDone=false,done=false;int encoding=AudioFormat.ENCODING_PCM_16BIT,channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);long count=0;double sum=0;long limit=SystemClock.uptimeMillis()+20000;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!done&&SystemClock.uptimeMillis()<limit){
                if(!inputDone){int index=decoder.dequeueInputBuffer(1000);if(index>=0){ByteBuffer input=decoder.getInputBuffer(index);input.clear();int size=extractor.readSampleData(input,0);if(size<0){decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{decoder.queueInputBuffer(index,0,size,extractor.getSampleTime(),0);extractor.advance();}}}
                int index=decoder.dequeueOutputBuffer(info,1000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat output=decoder.getOutputFormat();channels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);rate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE);encoding=output.containsKey(MediaFormat.KEY_PCM_ENCODING)?output.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;}
                else if(index>=0){ByteBuffer output=decoder.getOutputBuffer(index);if(output!=null&&info.size>0){ByteBuffer pcm=output.duplicate().order(ByteOrder.LITTLE_ENDIAN);pcm.position(info.offset);pcm.limit(info.offset+info.size);int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;check(encoding==AudioFormat.ENCODING_PCM_FLOAT||encoding==AudioFormat.ENCODING_PCM_16BIT,"Unsupported PCM measurement format");int samples=pcm.remaining()/bytes;for(int i=0;i<samples;i++){float value=bytes==4?pcm.getFloat():pcm.getShort()/32768f;long pts=info.presentationTimeUs+(long)(i/channels)*1_000_000/rate;if(pts>=100_000&&pts<600_000){sum+=(double)value*value;count++;}}}done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0||info.presentationTimeUs>650_000;decoder.releaseOutputBuffer(index,false);}
            }
            check(count>1000,"Decoded too little audio for measurement: "+count);return Math.sqrt(sum/count);
        }finally{if(decoder!=null){try{decoder.stop();}catch(RuntimeException ignored){}decoder.release();}extractor.release();}
    }
    private static double[] rgb(Bitmap image,double x,double y){int color=image.getPixel(Math.max(0,Math.min(image.getWidth()-1,(int)(image.getWidth()*x))),Math.max(0,Math.min(image.getHeight()-1,(int)(image.getHeight()*y))));return new double[]{Color.red(color)/255d,Color.green(color)/255d,Color.blue(color)/255d};}
    private static double mean(Bitmap image,int channel){double total=0;int count=0;for(int y=0;y<image.getHeight();y+=2)for(int x=0;x<image.getWidth();x+=2){int color=image.getPixel(x,y);total+=channel==0?Color.red(color):channel==1?Color.green(color):Color.blue(color);count++;}return total/count/255;}
    private static double edgeEnergy(Bitmap image){double sum=0;int n=0;for(int y=1;y<image.getHeight();y+=2)for(int x=1;x<image.getWidth();x+=2){int a=image.getPixel(x,y),b=image.getPixel(x-1,y),c=image.getPixel(x,y-1);sum+=Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b))+Math.abs(Color.red(a)-Color.red(c))+Math.abs(Color.green(a)-Color.green(c))+Math.abs(Color.blue(a)-Color.blue(c));n+=6;}return sum/Math.max(1,n);}
    private static double whiteFraction(Bitmap image){int white=0,total=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int pixel=image.getPixel(x,y);if(Color.red(pixel)>200&&Color.green(pixel)>200&&Color.blue(pixel)>200)white++;total++;}return (double)white/total;}
    private static int distinctPixels(Bitmap image){java.util.Set<Integer> colors=new java.util.HashSet<>();for(int y=0;y<image.getHeight();y+=3)for(int x=0;x<image.getWidth();x+=3){colors.add(image.getPixel(x,y));if(colors.size()>32)return colors.size();}return colors.size();}
}
