package local.jc.mainraw;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class NativeRawTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static byte[] read(File f)throws Exception{return Files.readAllBytes(f.toPath());}
    static void reject(byte[] b)throws Exception{try{NativeRaw.metadata(b);throw new AssertionError("Accepted invalid metadata");}catch(IOException expected){}}
    static int entry(byte[] bytes,int tag){ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<b.getInt(12);i++){int p=b.getInt(20)+i*16;if(b.getInt(p)==tag)return p;}throw new AssertionError("missing tag");}
    public static void main(String[] args)throws Exception{
        File cap=new File(args[0]),assets=new File(args[1]),out=new File(args[2]);out.mkdirs();
        String manifest=new String(read(new File(args[3])),"UTF-8");
        ArrayList<NativeCaptureIndex.Pair> indexed=NativeCaptureIndex.decode(manifest);check(indexed.size()==4,"all four same-time groups indexed");
        for(NativeCaptureIndex.Pair p:indexed){check(Integer.parseInt(p.frame)==2781+p.index/2,"metadata linked to correct source frame");check(p.a.name.contains("frame["+p.frame+"]")&&p.b.name.contains("frame["+p.frame+"]"),"no cross-frame branch merge");}
        StringBuilder filtered=new StringBuilder();for(String line:manifest.split("\\n"))if(!(line.contains("FormatConvertorInstance1")&&line.contains("frame[2781]")&&line.contains("_output")))filtered.append(line).append('\n');String broken=filtered.toString();
        check(broken.length()<manifest.length(),"negative test actually removed paired output");
        try{NativeCaptureIndex.decode(broken);throw new AssertionError("missing paired converter association accepted");}catch(IOException expected){}
        try{NativeCaptureIndex.decode(manifest+manifest.split("\\n")[0]+"\n");throw new AssertionError("duplicate manifest entry accepted");}catch(IOException expected){}
        String crossed=manifest.replace("IMG_20260928231916_[FormatConvertorInstance1]","IMG_20260928231917_[FormatConvertorInstance1]");
        check(NativeCaptureIndex.decode(crossed).size()==4,"wall-clock second boundary must not break same-frame association");
        File[] list=cap.listFiles();Arrays.sort(list);byte[] first=null;NativeRaw.Metadata main=null;
        for(File f:list)if(f.getName().endsWith("phymetadata.bin"))NativeRaw.metadata(read(f));
        for(int i=0;i<8;i+=2){NativeRaw.Metadata a=null,b=null;
            for(File f:list){if(f.getName().endsWith("port0_index"+i+"_buffer0_metadata.bin")){byte[] data=read(f);a=NativeRaw.metadata(data);if(i==0){first=data;main=a;}}
                if(f.getName().endsWith("port1_index"+(i+1)+"_buffer0_metadata.bin"))b=NativeRaw.metadata(read(f));}
            check(a!=null&&b!=null,"pair metadata present");NativeRaw.pair(a,b);
        }
        byte[] bad=first.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(entry(bad,0xe0002)+8,51);reject(bad);
        bad=first.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(entry(bad,0x80560009)+8,4);reject(bad);
        bad=first.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(entry(bad,0x8003000c)+8,Integer.MAX_VALUE);reject(bad);
        bad=first.clone();bad[entry(bad,0xe0002)+12]=2;reject(bad);
        reject(Arrays.copyOf(first,70));
        NativeRaw.Metadata other=NativeRaw.metadata(first);other.timestamp++;try{NativeRaw.pair(main,other);throw new AssertionError("different frame accepted");}catch(IOException expected){}
        check(NativeRaw.merge(1100,800)==1100,"dark samples must remain exactly unchanged");check(NativeRaw.merge(16383,200)==17344,"highlights use paired branch");
        check(NativeRaw.merge16(16383,1023)==58052,"UInt16 preserves full paired white without clipping");
        for(int v=0;v<=12000;v++)check(Math.abs(NativeRaw.merge16(v,0)-v*.5)<=.5,"uniform scale rounding bound");
        // Ideal linear matched branches remain monotonic across the blend; no tone curve.
        int previous=0;for(int y=64;y<=1023;y++){int x=Math.min(16383,1024+120*(y-64)),merged=NativeRaw.merge16(x,y);check(merged>=previous,"ideal branch blend monotonic");check(merged==(1024+120*(y-64))/2,"matched linear branches unchanged by blend");previous=merged;}
        File a=null,b=null;for(File f:list){if(f.getName().contains("frame[2781]_input")&&f.getName().endsWith("_7168.raw"))a=f;if(f.getName().contains("frame[2781]_input")&&f.getName().endsWith("_5120.raw"))b=f;}
        for(int k=0;k<3;k++)try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(new File(out,"java_"+k+".dng")))){NativeRaw.write(a,b,stream,read(new File(assets,"dng_"+k+".header")),main,k,"2026:09:28 23:19:16");}
        System.out.println("PASS: actual hash-based index maps all four groups; rejects missing pairing/duplicates; four ISO50 paired groups; reject wrong ISO/mode/type/bounds/truncation/timestamp; preserve dark values; generated three DNGs");
    }
}
