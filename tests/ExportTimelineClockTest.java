package com.personal.sonyvegasvideo;

/** Codec timestamps are observations: only the first decodable video picture may be anchored. */
public final class ExportTimelineClockTest {
    private static int checks;
    public static void main(String[] args){
        ExportTimelineClock clock=new ExportTimelineClock();clock.videoTrack(7);
        check(clock.presentationTimeUs(2,-21333,100,false,false)==-21333);
        check(clock.presentationTimeUs(7,83333,0,false,false)==83333);
        check(clock.presentationTimeUs(7,83333,100,true,false)==0);
        check(clock.presentationTimeUs(2,0,100,false,false)==0);
        check(clock.presentationTimeUs(7,208333,100,false,false)==208333);
        check(clock.presentationTimeUs(7,166667,100,false,false)==166667); // B-frame PTS may decrease.
        check(clock.presentationTimeUs(7,1600000,0,false,true)==1600000);
        check(clock.presentationTimeUs(7,1666666,100,true,false)==1666666); // A later keyframe is untouched.
        ExportTimelineClock zero=new ExportTimelineClock();zero.videoTrack(0);
        check(zero.presentationTimeUs(0,0,120,true,false)==0);
        check(zero.presentationTimeUs(0,41667,120,false,false)==41667);
        ExportTimelineClock negative=new ExportTimelineClock();negative.videoTrack(0);
        check(negative.presentationTimeUs(0,-1000,120,true,false)==-1000);
        check(negative.presentationTimeUs(0,2000,120,false,false)==2000);
        ExportTimelineClock multiple=new ExportTimelineClock();multiple.videoTrack(0);multiple.videoTrack(3);
        check(multiple.presentationTimeUs(3,50000,120,true,false)==0);
        check(multiple.presentationTimeUs(0,90000,120,true,false)==0);
        check(multiple.presentationTimeUs(3,100000,120,false,false)==100000);
        ExportTimelineClock broken=new ExportTimelineClock();broken.videoTrack(0);boolean rejected=false;
        try{broken.presentationTimeUs(0,83333,120,false,false);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected);check(broken.presentationTimeUs(0,83333,120,true,false)==0);
        for(int i=1;i<200;i++){
            ExportTimelineClock independent=new ExportTimelineClock();independent.videoTrack(0);
            check(independent.presentationTimeUs(0,i*1000L,100,true,false)==0);
            for(int j=1;j<8;j++){long timestamp=i*1000L+j*41667L;check(independent.presentationTimeUs(0,timestamp,100,j==7,false)==timestamp);}
            check(independent.presentationTimeUs(1,i*1000L,100,false,false)==i*1000L);
        }
        System.out.println("ExportTimelineClockTest OK: "+checks+" checks");
    }
    private static void check(boolean valid){checks++;if(!valid)throw new AssertionError("Clock check "+checks);}
}
