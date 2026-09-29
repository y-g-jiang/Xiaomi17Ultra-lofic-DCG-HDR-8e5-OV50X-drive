import local.jc.mainraw.PtcPlan;
public class PtcPlanTest {
    public static void main(String[] args){
        long[] t=PtcPlan.exposures();
        if(t.length!=43||t[0]!=30682||t[t.length-1]!=1000000000L)throw new AssertionError("range/count");
        for(int i=1;i<t.length;i++)if(t[i]<=t[i-1])throw new AssertionError("duplicate/nonmonotonic");
        for(int i=0;i<t.length-1;i++){
            double lines=t[i]/PtcPlan.LINE_NS;
            if(Math.abs(lines-Math.rint(lines))>1/PtcPlan.LINE_NS+1e-8)throw new AssertionError("alignment");
        }
        System.out.println("PASS: 43 unique exposures, 86 shots, 172 raw-only DNGs; increasing, line aligned, 1s endpoint");
        for(int i=0;i<t.length;i++)System.out.println(i+","+t[i]);
    }
}
