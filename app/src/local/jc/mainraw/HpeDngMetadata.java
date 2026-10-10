package local.jc.mainraw;

import java.io.*;

/** Fixes only the derived DNG's stale WhiteLevel; original capture metadata is retained. */
final class HpeDngMetadata {
    private static long u32(RandomAccessFile f)throws IOException{return (long)f.readUnsignedByte()|((long)f.readUnsignedByte()<<8)|((long)f.readUnsignedByte()<<16)|((long)f.readUnsignedByte()<<24);}
    private static int u16(RandomAccessFile f)throws IOException{return f.readUnsignedByte()|(f.readUnsignedByte()<<8);}
    static void setContainerWhite(File file,int reported,int codeLimit)throws IOException {
        try(RandomAccessFile f=new RandomAccessFile(file,"rw")){
            if(f.readUnsignedByte()!=73||f.readUnsignedByte()!=73||u16(f)!=42)throw new IOException("Unsupported HPE DNG TIFF header");
            long ifd=u32(f);if(ifd<8||ifd>=f.length()-2)throw new IOException("Invalid HPE DNG IFD");
            f.seek(ifd);int count=u16(f);
            for(int i=0;i<count;i++){
                long at=ifd+2L+i*12;if(at>f.length()-12)throw new IOException("Truncated HPE DNG IFD");
                f.seek(at);int tag=u16(f),type=u16(f);long n=u32(f),value=u32(f);
                if(tag==50717){
                    if(type!=4||n!=1||value!=reported)throw new IOException("Unexpected reported HPE WhiteLevel");
                    f.seek(at+8);for(int k=0;k<4;k++)f.write((codeLimit>>>(k*8))&255);f.getFD().sync();return;
                }
            }
            throw new IOException("Missing HPE WhiteLevel");
        }
    }
}
