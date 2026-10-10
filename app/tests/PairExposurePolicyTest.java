package local.jc.mainraw;
public class PairExposurePolicyTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        PairExposurePolicy.Plan bright=PairExposurePolicy.meter(1_000_000,70);
        check(bright.exposureNs==1_000_000 && bright.gain==1);
        PairExposurePolicy.Plan dark=PairExposurePolicy.meter(100_000_000,140);
        check(dark.exposureNs==33_333_333 && dark.gain>6 && dark.gain<6.001);
        check(PairExposurePolicy.meter(1_000_000_000,7000).gain==16);
        check(PairExposurePolicy.safe50(33_333_333,33_320_006));
        check(!PairExposurePolicy.safe50(33_333_334,33_320_006));
        check(!PairExposurePolicy.safe50(33_333_333,33_333_334));
        check(!PairExposurePolicy.safe50(0,0));
        check(PairExposurePolicy.fixedHdr(33_320_006));
        check(!PairExposurePolicy.fixedHdr(1_000_000));
        check(!PairExposurePolicy.fixedHdr(32_000_000));
        check(!PairExposurePolicy.fixedHdr(33_400_000));
        for(long ns:new long[]{0,-1})try{PairExposurePolicy.meter(ns,70);throw new AssertionError();}catch(IllegalArgumentException expected){}
        check(PairExposurePolicy.tripod(10_000_000,700).exposureNs==100_000_000);
        check(PairExposurePolicy.tripod(10_000_000,700).gain==1);
        check(PairExposurePolicy.tripod(100_000_000,7000).exposureNs==1_000_000_000);
        check(PairExposurePolicy.tripod50(100_000_000,99_990_000));
        check(!PairExposurePolicy.tripod50(1_000_000_001,1_000_000_000));
        check(PairExposurePolicy.hdrMatches(245452,245451));
        check(!PairExposurePolicy.hdrMatches(245452,33320006));
        System.out.println("Safety shutter policy checks passed");
    }
}
