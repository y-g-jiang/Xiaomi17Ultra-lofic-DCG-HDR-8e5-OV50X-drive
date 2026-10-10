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
  h=new int[1024];h[543]=5;h[544]=5;
  long first=NativeEttr.FIRST_NS;double target=2*first+0.76955*NativeEttr.LINE_NS;
  r=NativeEttr.fromHistogram(h,first,true);check(r.multiplier==2&&r.exposureNs>=target&&r.exposureNs<target+NativeEttr.LINE_NS+1&&!r.limited);
  NativeEttr.Result legacy=NativeEttr.fromHistogram(h,first,false);check(r.exposureNs>=legacy.exposureNs);
  r=NativeEttr.fromHistogram(h,3750000,true);legacy=NativeEttr.fromHistogram(h,3750000,false);check(r.exposureNs>legacy.exposureNs);
  h=new int[1024];h[1023]=10;r=NativeEttr.fromHistogram(h,first,true);check(r.multiplier==1&&r.exposureNs==first&&!r.limited);
  r=NativeEttr.fromHistogram(h,first,false);check(r.exposureNs==first&&!r.limited);
  h=new int[1024];h[64]=10;r=NativeEttr.fromHistogram(h,first,true);check(r.exposureNs==NativeEttr.MAX_NS&&r.limited);
  h=new int[1024];h[65]=10;r=NativeEttr.fromHistogram(h,3750000,true);check(r.exposureNs==NativeEttr.MAX_NS&&r.limited);
  byte[] packed=new byte[15728640];
  for(int i=0;i<packed.length;i+=5){packed[i]=0;packed[i+1]=85;packed[i+2]=(byte)170;packed[i+3]=(byte)255;packed[i+4]=(byte)228;}
  int[] actual=NativeEttr.histogram(packed);int count=packed.length/5;
  check(actual[0]==count&&actual[341]==count&&actual[682]==count&&actual[1023]==count);
  try{NativeEttr.histogram(new byte[5]);throw new AssertionError();}catch(java.io.IOException expected){}
  int[][] channels=new int[4][1024];channels[0][300]=10;channels[1][800]=10;channels[2][900]=10;channels[3][1023]=10;
  r=NativeEttr.fromChannels(channels,first);check(r.selectedChannel==0&&r.mean==300&&r.exposureNs>first);
  check(r.channelMeans[0]==300&&r.channelMeans[3]==1023);
  channels[0]=new int[1024];channels[0][1023]=10;
  r=NativeEttr.fromChannels(channels,first);check(r.selectedChannel==1&&r.mean==800);
  for(int c=0;c<4;c++){channels[c]=new int[1024];channels[c][1023]=10;}
  r=NativeEttr.fromChannels(channels,first);check(r.selectedChannel==0&&r.exposureNs==first);
  channels[3]=new int[1024];channels[3][64]=10;r=NativeEttr.fromChannels(channels,first);check(r.selectedChannel==3&&r.limited&&r.exposureNs==NativeEttr.MAX_NS);
  int[][] decoded=NativeEttr.channelHistograms(packed);
  check(decoded[0][0]==1572864&&decoded[0][682]==1572864);
  check(decoded[1][341]==1572864&&decoded[1][1023]==1572864);
  check(decoded[2][0]==1572864&&decoded[3][1023]==1572864);
  System.out.println("PASS legacy Top10, clipping, black-only and limits; unity timing correction, clipping and limits");
 }
}
