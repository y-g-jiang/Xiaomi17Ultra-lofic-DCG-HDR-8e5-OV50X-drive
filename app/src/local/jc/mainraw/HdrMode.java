package local.jc.mainraw;

import java.util.*;

/** Actual OEM session words, derived from this phone's capability table and native consumer. */
public final class HdrMode {
    public static final String LOFIC="com.xiaomi.sessionparams.enableLofic";
    public static final String DCG="org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode";
    public static final String EXPOSURES="org.codeaurora.qcamera3.sessionParameters.numHDRexposure";
    public static final String CAPS="org.codeaurora.qcamera3.supportedHDRmodes.HDRDCGModes";
    public final String id, label;
    public final Map<String,Integer> parameters;
    public final int mode, longBits, shortBits;
    public HdrMode(String id,String label,Map<String,Integer> parameters,int packed){
        this.id=id;this.label=label;this.parameters=Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        this.mode=packed&255;this.longBits=(packed>>>8)&255;this.shortBits=(packed>>>16)&255;
    }
    public static int pack(int mode,int longBits,int shortBits){
        if(mode<0||mode>5||longBits<0||longBits>32||shortBits<0||shortBits>32)throw new IllegalArgumentException("Invalid DCG mode/bit depth");
        return mode|(longBits<<8)|(shortBits<<16);
    }
    public static List<HdrMode> supported(Set<String> requests,Set<String> sessions,int[] mainCaps){
        ArrayList<HdrMode> out=new ArrayList<>();out.add(new HdrMode("stock","原厂默认 RAW",Collections.emptyMap(),0));
        boolean l=requests.contains(LOFIC)&&sessions.contains(LOFIC);
        boolean dcg=requests.contains(DCG)&&sessions.contains(DCG)&&requests.contains(EXPOSURES)&&sessions.contains(EXPOSURES);
        if(l)out.add(new HdrMode("lofic_session","LOFIC 会话请求（设备自行选模式）",Collections.singletonMap(LOFIC,1),0));
        if(!dcg||mainCaps==null)return out;
        HashSet<Integer> seen=new HashSet<>();
        for(int word:mainCaps){
            if(!seen.add(word))continue;
            int m=word&255,lb=(word>>>8)&255,sb=(word>>>16)&255;
            // Limit presets to the layouts actually inspected on the connected ROM.
            if(word!=pack(4,14,0)&&word!=pack(4,12,0)&&word!=pack(5,14,10))continue;
            LinkedHashMap<String,Integer> p=new LinkedHashMap<>();
            if(l)p.put(LOFIC,0);p.put(DCG,word);p.put(EXPOSURES,m==5?2:1);
            String bits=sb==0?Integer.toString(lb):lb+"+"+sb;
            out.add(new HdrMode("dcg_"+Integer.toHexString(word),"DCG HDR "+bits+" 位请求 · 模式 "+m,p,word));
            if(l&&m==5){p=new LinkedHashMap<>(p);p.put(LOFIC,1);out.add(new HdrMode("lofic_dcg_"+Integer.toHexString(word),"LOFIC ＋ DCG "+bits+" 位请求",p,word));}
        }
        return out;
    }
    public boolean isDefault(){return parameters.isEmpty();}
    public boolean asksLofic(){return Integer.valueOf(1).equals(parameters.get(LOFIC));}
    public void rejectConflicts(Set<String> customKeys){
        if(isDefault())return;
        for(String key:customKeys)if(key.equals(LOFIC)||key.equals(DCG)||key.equals(EXPOSURES))throw new IllegalArgumentException("HDR 预设与高级参数重复："+key+"；请改用原厂默认预设或移除重复键");
    }
}
