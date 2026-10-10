package local.jc.mainraw;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Associates native branches with metadata using converter-output hashes, never listing order. */
public final class NativeCaptureIndex {
    public static final class Entry {String hash,name;long length;Entry(String h,long n,String s){hash=h;length=n;name=s;}}
    private static Map<String,Entry> manifest(String text)throws Exception {
        Map<String,Entry> map=new TreeMap<>();
        for(String line:text.split("\n")){
            String[] v=line.trim().split("\\|",-1);if(v.length!=3||!v[0].matches("[0-9a-f]{64}")||!v[1].matches("[0-9]+")||!v[2].matches("[A-Za-z0-9_\\[\\].-]+"))throw new IOException("Unexpected capture manifest line: "+line);
            Entry e=new Entry(v[0],Long.parseLong(v[1]),v[2]);if(e.length<=0||e.length>30_000_000||map.put(e.name,e)!=null)throw new IOException("Invalid/duplicate capture entry");
        }return map;
    }
    private static Entry required(Map<String,Entry> entries,String name)throws IOException {Entry e=entries.get(name);if(e==null)throw new IOException("缺少同帧数据："+name);return e;}
    public static final class Pair {Entry a,b,ma,mb,pa,pb;String frame,prefix;int index;NativeRaw.Metadata meta;}
    public static ArrayList<Pair> decode(String text)throws Exception {
        Map<String,Entry> entries=manifest(text);ArrayList<Pair> pairs=new ArrayList<>();
        Pattern rawPattern=Pattern.compile("IMG_([0-9]{14})_\\[FormatConvertorInstance0\\]_frame\\[([0-9]+)\\]_input\\[0\\]\\[0\\]_idx\\[[0-9]+\\]_4096x3072_7168\\.raw");
        Set<String> convertedNames=new HashSet<>();
        for(Entry a:entries.values()){
            Matcher match=rawPattern.matcher(a.name);if(!match.matches())continue;
            Pair pair=new Pair();pair.a=a;pair.frame=match.group(2);String stem="_[FormatConvertorInstance";
            Entry converted=null;
            for(Entry e:entries.values()){
                if(e.name.contains(stem+"0]_frame["+pair.frame+"]_output")){if(converted!=null)throw new IOException("Ambiguous frame output");converted=e;}
                if(e.name.contains(stem+"1]_frame["+pair.frame+"]_input")&&e.name.endsWith("_4096x3072_5120.raw")){if(pair.b!=null)throw new IOException("Ambiguous paired input");pair.b=e;}
            }
            if(converted==null||pair.b==null)throw new IOException("同帧 RAW14 / RAW10 不完整");
            Entry allin=null;
            for(Entry e:entries.values())if(e.name.matches("[0-9]{17}_AllinOne_4096x3072_input_[0-9]{2}\\.RAW")&&e.hash.equals(converted.hash)){
                if(allin!=null)throw new IOException("Ambiguous metadata association");allin=e;
            }
            if(allin==null)throw new IOException("无法把转换帧与原生元数据可靠对应");
            pair.index=Integer.parseInt(allin.name.substring(allin.name.length()-6,allin.name.length()-4));
            if(pair.index%2!=0)throw new IOException("Unexpected primary branch index");pair.prefix=allin.name.substring(0,17);
            String base=pair.prefix+"_AllinOne_4096x3072_input_00_";
            pair.ma=required(entries,base+"port0_index"+pair.index+"_buffer0_metadata.bin");
            Entry convertedB=null;for(Entry e:entries.values())if(e.name.contains(stem+"1]_frame["+pair.frame+"]_output")){if(convertedB!=null)throw new IOException("Ambiguous paired output");convertedB=e;}
            if(convertedB==null)throw new IOException("Missing paired converter output");
            Entry allinB=null;for(Entry e:entries.values())if(e.name.matches("[0-9]{17}_AllinOne_4096x3072_input_[0-9]{2}\\.RAW")&&e.hash.equals(convertedB.hash)){
                if(allinB!=null)throw new IOException("Ambiguous paired metadata association");allinB=e;
            }
            if(allinB==null)throw new IOException("Missing paired AllinOne input");
            int bi=Integer.parseInt(allinB.name.substring(allinB.name.length()-6,allinB.name.length()-4));
            String bb=allinB.name.substring(0,17)+"_AllinOne_4096x3072_input_00_";
            pair.mb=required(entries,bb+"port1_index"+bi+"_buffer0_metadata.bin");
            pair.pa=required(entries,base+"port0_index"+pair.index+"_buffer0_phymetadata.bin");
            pair.pb=required(entries,bb+"port1_index"+bi+"_buffer0_phymetadata.bin");
            if(!convertedNames.add(allin.name)||!convertedNames.add(allinB.name))throw new IOException("Duplicate paired group");pairs.add(pair);
        }
        if(pairs.isEmpty())throw new IOException("原生相机未输出完整的模式5 RAW14＋RAW10配对；本次不能生成 LOFIC HDR。会话诊断已保留，需要检查实际算法策略，不能仅凭预览或场景判断。");
        if(pairs.size()>8)throw new IOException("一次采集超过8组限制，请缩短采集窗口。");
        int nativeBuffers=0;
        for(Entry e:entries.values())if(e.name.matches("[0-9]{17}_AllinOne_4096x3072_input_[0-9]{2}\\.RAW")){
            nativeBuffers++;int index=Integer.parseInt(e.name.substring(e.name.length()-6,e.name.length()-4));
            if(!convertedNames.contains(e.name))throw new IOException("有原生时间组缺少完整的转换前配对，拒绝悄悄漏帧");
        }
        if(nativeBuffers!=pairs.size()*2)throw new IOException("原生缓冲区数量与独立时间组不一致");
        return pairs;
    }
}
