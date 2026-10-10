package local.jc.mainraw;
/** User units, bounded to the supported product exposure range. */
final class PairManualPolicy {
    static long roundingTolerance(long exposureNs){return Math.max(1,Math.min(60,(exposureNs+16777215L)/16777216L));}
    static long shutter(String text) {
        String s=text.trim();double seconds;
        String[] fraction=s.split("/",-1);
        if(fraction.length==2)seconds=Double.parseDouble(fraction[0].trim())/Double.parseDouble(fraction[1].trim());
        else if(fraction.length==1)seconds=Double.parseDouble(s);
        else throw new IllegalArgumentException("快门格式应为 1/30 或 0.1 秒");
        if(!Double.isFinite(seconds)||seconds<.0001||seconds>1)throw new IllegalArgumentException("快门范围：1/10000～1 秒");
        return Math.round(seconds*1e9);
    }
    static long delay(String text) {
        double seconds=Double.parseDouble(text.trim());
        if(!Double.isFinite(seconds)||seconds<0||seconds>60)throw new IllegalArgumentException("延时范围：0～60 秒");
        return Math.round(seconds*1000);
    }
}
