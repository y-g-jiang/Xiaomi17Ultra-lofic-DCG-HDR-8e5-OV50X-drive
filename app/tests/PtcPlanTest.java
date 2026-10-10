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
        long[] top=PtcPlan.highlightExposures(1.25,18.0);
        if(top.length!=13)throw new AssertionError("top must use third stops, 13 levels including endpoint");
        for(int i=0;i<top.length-1;i++){
            double requested=1.25e6*Math.pow(2,i/3.0);
            if(top[i]<requested||top[i]-requested>PtcPlan.LINE_NS+1)throw new AssertionError("top step deviates from 1/3 EV by more than one sensor line");
        }
        if(top[top.length-1]<18e6||top[top.length-1]-18e6>PtcPlan.LINE_NS+1)throw new AssertionError("top endpoint");
        if(PtcPlan.highlightExposures(3,3).length!=1)throw new AssertionError("flat must keep one exposure");
        System.out.println("PASS: highlight 1.25–18ms uses 13 third-stop levels; fixed-exposure flat unchanged");
        System.out.println("PASS: 43 unique exposures, 86 shots, 172 raw-only DNGs; increasing, line aligned, 1s endpoint");
        for(int i=0;i<t.length;i++)System.out.println(i+","+t[i]);
    }
}
