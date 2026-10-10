package local.jc.mainraw;
public final class PairManualPolicyTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        check(PairManualPolicy.shutter("1/30")==33333333);
        check(PairManualPolicy.shutter("0.05")==50000000);
        check(PairManualPolicy.shutter("1/10000")==100000);
        check(PairManualPolicy.shutter("1")==1000000000);
        check(PairManualPolicy.delay("0")==0&&PairManualPolicy.delay("2.5")==2500&&PairManualPolicy.delay("60")==60000);
        for(String s:new String[]{"0","1/0","NaN","Infinity","-1","1/20000","1.001","1/2/3"}){
            try{PairManualPolicy.shutter(s);throw new AssertionError(s);}catch(IllegalArgumentException expected){}
        }
        for(String s:new String[]{"NaN","Infinity","-1","60.1"}){
            try{PairManualPolicy.delay(s);throw new AssertionError(s);}catch(IllegalArgumentException expected){}
        }
        check(PairManualPolicy.roundingTolerance(500000000)==30);
        check(PairManualPolicy.roundingTolerance(1000000000)==60);
        check(PairManualPolicy.roundingTolerance(100000)==1);
        System.out.println("PASS manual shutter and delay range/unit checks");
    }
}
