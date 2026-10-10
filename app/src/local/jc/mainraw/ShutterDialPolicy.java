package local.jc.mainraw;

/** Conventional camera shutter stops, in nanoseconds. */
final class ShutterDialPolicy {
    private static final double[] DENOMINATORS={10000,8000,6400,5000,4000,3200,2500,2000,1600,1250,1000,800,640,500,400,320,250,200,160,125,100,80,60,50,40,30,25,20,15,13,10,8,6,5,4,3,2.5,2,1.6,1.3,1};
    static int count(){return DENOMINATORS.length;}
    static long exposure(int index){return Math.round(1e9/DENOMINATORS[Math.max(0,Math.min(count()-1,index))]);}
    static int nearest(long ns){int best=0;double error=Double.POSITIVE_INFINITY;for(int i=0;i<count();i++){double e=Math.abs(Math.log(exposure(i)/(double)Math.max(1,ns)));if(e<error){error=e;best=i;}}return best;}
    static String label(int index){double d=DENOMINATORS[Math.max(0,Math.min(count()-1,index))];return d==1?"1 s":"1/"+(d==Math.rint(d)?Long.toString((long)d):Double.toString(d))+" s";}
}
