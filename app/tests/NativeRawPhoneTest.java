package local.jc.mainraw;
import java.io.*;

/** Offline codec execution under Android ART; no camera or app-permission mutation. */
public final class NativeRawPhoneTest {
    static byte[] read(String path,int max)throws Exception {try(InputStream in=new FileInputStream(path)){return NativeRaw.bytes(in,max);}}
    public static void main(String[] args)throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("raw14 raw10 mainMetadata pairedMetadata header output");
        NativeRaw.Metadata m=NativeRaw.metadata(read(args[2],4_000_000));NativeRaw.pair(m,NativeRaw.metadata(read(args[3],4_000_000)));
        File target=new File(args[5]);if(target.exists())throw new IOException("Refusing to overwrite existing output");
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){NativeRaw.write(new File(args[0]),new File(args[1]),out,read(args[4],16384),m,2,"2026:09:28 23:19:16");}
        System.out.println("ART UInt16 codec complete; ISO="+m.iso+" timestamp="+m.timestamp+" bytes="+target.length());
    }
}
