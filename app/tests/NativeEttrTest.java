package local.jc.mainraw;
public final class NativeEttrTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  int[] h=new int[1024];h[100]=100000;h[543]=5;h[544]=5;
  NativeEttr.Result r=NativeEttr.fromHistogram(h,3750000);check(r.mean==543.5&&r.multiplier==2&&r.exposureNs>=7500000&&r.exposureNs<7530682&&!r.limited);
  h=new int[1024];h[1023]=10;r=NativeEttr.fromHistogram(h,3750000);check(r.multiplier==1&&r.exposureNs>=3750000&&r.exposureNs<3780682);
  h=new int[1024];h[65]=10;r=NativeEttr.fromHistogram(h,3750000);check(r.exposureNs==1000000000&&r.limited);
  h=new int[1024];h[64]=10;r=NativeEttr.fromHistogram(h,3750000);check(r.exposureNs==1000000000&&r.limited);
  h=new int[1024];h[800]=9;try{NativeEttr.fromHistogram(h,3750000);throw new AssertionError();}catch(java.io.IOException expected){}
  System.out.println("PASS top10 repeats, black subtraction, exactly doubled target with at most one line alignment, clipped first frame, max limit, black-only input, incomplete histogram");
 }
}