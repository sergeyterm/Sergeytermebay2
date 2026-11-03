package com.personal.sonyvegasvideo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Reads the MP4 movie timeline without loading media payloads into memory. */
public final class Mp4Duration {
    private Mp4Duration() { }
    public static long read(FileChannel channel, long start, long length) throws IOException {
        long size=channel.size();
        if(start<0||start>size||length < -1)return -1;
        long available=size-start;
        long end=start+(length<0?available:Math.min(length,available));
        return boxes(channel,start,end,false,new int[]{4096});
    }
    private static long boxes(FileChannel channel,long position,long end,boolean movie,int[] budget)throws IOException {
        while(position<=end-8&&budget[0]-->0){
            ByteBuffer header=readAt(channel,position,8);if(header==null)return -1;
            long size=Integer.toUnsignedLong(header.getInt());int type=header.getInt();int headerSize=8;
            if(size==1){ByteBuffer large=readAt(channel,position+8,8);if(large==null||end-position<16)return -1;size=large.getLong();headerSize=16;}
            else if(size==0)size=end-position;
            if(size<headerSize||size>end-position)return -1;
            if(!movie&&type==0x6d6f6f76){long duration=boxes(channel,position+headerSize,position+size,true,budget);if(duration>0)return duration;}
            if(movie&&type==0x6d766864)return movieHeader(channel,position+headerSize,size-headerSize);
            position+=size;
        }
        return -1;
    }
    private static long movieHeader(FileChannel channel,long position,long size)throws IOException {
        if(size<20)return -1;
        ByteBuffer data=readAt(channel,position,(int)Math.min(size,32));if(data==null)return -1;
        int version=Byte.toUnsignedInt(data.get());long scale,duration;
        if(version==0){data.position(12);scale=Integer.toUnsignedLong(data.getInt());duration=Integer.toUnsignedLong(data.getInt());if(duration==0xffffffffL)return -1;}
        else if(version==1&&size>=32){data.position(20);scale=Integer.toUnsignedLong(data.getInt());duration=data.getLong();}
        else return -1;
        if(scale==0||duration<=0||duration/scale>Long.MAX_VALUE/1_000_000)return -1;
        long seconds=duration/scale,remainder=(duration%scale)*1_000_000/scale;
        if(seconds*1_000_000>Long.MAX_VALUE-remainder)return -1;
        return seconds*1_000_000+remainder;
    }
    private static ByteBuffer readAt(FileChannel channel,long position,int size)throws IOException {
        ByteBuffer bytes=ByteBuffer.allocate(size);
        while(bytes.hasRemaining()){int count=channel.read(bytes,position+bytes.position());if(count<=0)return null;}
        bytes.flip();return bytes;
    }
}
