package local.jc.mainraw;
public class PairFocusPolicyTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        check(PairFocusPolicy.matches(1.04f,1.04f));
        check(!PairFocusPolicy.matches(1.04f,0));
        check(!PairFocusPolicy.matches(Float.NaN,0));
        check(!PairFocusPolicy.matches(1,Float.POSITIVE_INFINITY));
        check(!PairFocusPolicy.matches(-1,-1));
        String log="1000.000 Actuator[0] TargetPosition: 892(DAC:892)\n"
                +"1000.300 Actuator[0] TargetPosition: 892(DAC:892)\n"
                +"1000.600 Actuator[0] TargetPosition: 892(DAC:892)\n";
        check(PairFocusPolicy.stableDac(log,1000000)==892);
        check(PairFocusPolicy.stableDac(log,1000300)==-1);
        check(PairFocusPolicy.stableDac(log.replace("1000.600","1000.350"),1000000)==-1);
        check(PairFocusPolicy.stableDac(log+"1000.900 Actuator[0] TargetPosition: 552(DAC:552)\n",1000000)==-1);
        check(PairFocusPolicy.stableDac(log.replace("Actuator[0]","Actuator[1]"),1000000)==-1);
        System.out.println("Focus distance, freshness, settling and actuator-change rejection checks passed");
    }
}
