package com.personal.sonyvegasvideo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Mp4DurationTest {
    private static int count;
    public static void main(String[] args)throws Exception {
        byte[] six=movie(0,1000,6000);
        check(read(six,0,-1)==6_000_000);
        check(read(movie(1,90000,540000),0,-1)==6_000_000);
        check(read(movie(0,3,10),0,-1)==3_333_333);
        check(read(movie(0,0,6000),0,-1)==-1);
        check(read(movie(0,1000,0xffffffffL),0,-1)==-1);
        check(read(movie(1,1,Long.MAX_VALUE),0,-1)==-1);
        check(read(movie(2,1000,6000),0,-1)==-1);
        byte[] extended=ByteBuffer.allocate(six.length+8).putInt(1).putInt(0x6d6f6f76).putLong(six.length+8).put(six,8,six.length-8).array();
        check(read(extended,0,-1)==6_000_000);
        byte[] toEnd=six.clone();ByteBuffer.wrap(toEnd).putInt(0);
        check(read(toEnd,0,-1)==6_000_000);
        byte[] embedded=new byte[six.length+19];System.arraycopy(six,0,embedded,11,six.length);
        check(read(embedded,11,six.length)==6_000_000);
        check(read(embedded,11,12)==-1);
        check(read(six,-1,-1)==-1);
        check(read(six,Long.MAX_VALUE,-1)==-1);
        check(read(six,0,-2)==-1);
        byte[] negative=extended.clone();ByteBuffer.wrap(negative).putLong(8,Long.MIN_VALUE);
        check(read(negative,0,-1)==-1);
        for(int i=0;i<six.length;i++)check(read(java.util.Arrays.copyOf(six,i),0,-1)==-1);
        byte[] tooLarge=six.clone();ByteBuffer.wrap(tooLarge).putInt(Integer.MAX_VALUE);
        check(read(tooLarge,0,-1)==-1);
        if(args.length>0)try(FileChannel file=FileChannel.open(java.nio.file.Paths.get(args[0]),StandardOpenOption.READ)){check(Mp4Duration.read(file,0,-1)==6_000_000);}
        System.out.println("Mp4DurationTest OK: "+count+" checks");
    }
    private static byte[] movie(int version,int scale,long duration)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream data=new DataOutputStream(bytes);
        data.writeInt(version==1?40:28);data.writeInt(0x6d766864);data.writeInt(version<<24);
        if(version==1){data.writeLong(0);data.writeLong(0);data.writeInt(scale);data.writeLong(duration);}
        else {data.writeInt(0);data.writeInt(0);data.writeInt(scale);data.writeInt((int)duration);}
        byte[] header=bytes.toByteArray();return ByteBuffer.allocate(header.length+8).putInt(header.length+8).putInt(0x6d6f6f76).put(header).array();
    }
    private static long read(byte[] bytes,long start,long length)throws Exception {
        Path path=Files.createTempFile("vegas-duration-",".mp4");
        try {Files.write(path,bytes);try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ)){return Mp4Duration.read(channel,start,length);}}
        finally {Files.deleteIfExists(path);}
    }
    private static void check(boolean condition){count++;if(!condition)throw new AssertionError("Check "+count);}
}
