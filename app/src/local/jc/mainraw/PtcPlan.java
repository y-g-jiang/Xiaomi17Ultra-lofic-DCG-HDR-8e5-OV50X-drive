package local.jc.mainraw;
import java.util.*;
/** Mode5 line-aligned exposure scan. Nominal third-stop steps, duplicate times removed. */
public final class PtcPlan {
    public static final double LINE_NS=30681.405566;
    public static long[] exposures(){
        ArrayList<Long> out=new ArrayList<>();
        for(int k=0;LINE_NS*Math.pow(2,k/3.0)<1e9;k++){
            long lines=(long)Math.ceil(Math.pow(2,k/3.0)-1e-10);
            long ns=Math.min(1_000_000_000L,(long)Math.ceil(lines*LINE_NS));
            if(out.isEmpty()||out.get(out.size()-1)!=ns)out.add(ns);
        }
        if(out.get(out.size()-1)!=1_000_000_000L)out.add(1_000_000_000L);
        long[] result=new long[out.size()];for(int i=0;i<result.length;i++)result[i]=out.get(i);return result;
    }
    public static long[] highlightExposures(double lowMs,double highMs){
        if(!Double.isFinite(lowMs)||!Double.isFinite(highMs)||lowMs<LINE_NS/1e6||highMs<lowMs||highMs>1000)throw new IllegalArgumentException("要求最小一行至1000ms，终点不小于起点");
        ArrayList<Long> out=new ArrayList<>();
        for(int k=0;k<1000;k++){
            double target=lowMs*1e6*Math.pow(2,k/6.0);if(target>=highMs*1e6)break;
            long ns=Math.min(1000000000L,(long)Math.ceil(Math.ceil(target/LINE_NS-1e-10)*LINE_NS));
            if(out.isEmpty()||out.get(out.size()-1)!=ns)out.add(ns);
        }
        long end=Math.min(1000000000L,(long)Math.ceil(Math.ceil(highMs*1e6/LINE_NS-1e-10)*LINE_NS));
        if(out.isEmpty()||out.get(out.size()-1)!=end)out.add(end);
        long[] result=new long[out.size()];for(int i=0;i<result.length;i++)result[i]=out.get(i);return result;
    }
}
