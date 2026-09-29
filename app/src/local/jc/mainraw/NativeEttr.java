package local.jc.mainraw;
import java.io.*;

/** One calculation from the first RAW10; never schedules retries. */
public final class NativeEttr {
    public static final int BLACK=64,WHITE=1023,TOP=10;
    public static final long MIN_NS=23_821L,MAX_NS=1_000_000_000L;
    public static final long FIRST_NS=245452L; // ceil(8 mode5 line periods)
    public static final double LINE_NS=30681.405566;
    public static final class Result {
        public double mean,multiplier;
        public long exposureNs;
        public boolean limited;
    }
    public static Result fromHistogram(int[] histogram,long firstNs)throws IOException {
        if(histogram.length!=1024||firstNs<MIN_NS||firstNs>MAX_NS)throw new IOException("Invalid ETTR input");
        int remaining=TOP;long sum=0;
        for(int value=1023;value>=0&&remaining>0;value--){
            if(histogram[value]<0)throw new IOException("Invalid histogram");
            int take=Math.min(remaining,histogram[value]);sum+=(long)take*value;remaining-=take;
        }
        if(remaining!=0)throw new IOException("Fewer than 10 RAW pixels");
        Result r=new Result();r.mean=sum/(double)TOP;
        r.multiplier=r.mean>BLACK?(WHITE-BLACK)/(r.mean-BLACK):Double.POSITIVE_INFINITY;
        double target=firstNs*r.multiplier;
        r.exposureNs=Math.max(MIN_NS,Math.min(MAX_NS,(long)Math.ceil(Math.ceil(target/LINE_NS)*LINE_NS)));
        r.limited=target<MIN_NS||target>MAX_NS;
        return r;
    }
    public static Result measure(File raw10,long firstNs)throws IOException {
        if(raw10.length()!=15728640L)throw new IOException("Incomplete LOFIC RAW10");
        int[] histogram=new int[1024],row=new int[NativeRaw.W];byte[] packed=new byte[5120];
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(raw10)))){
            for(int y=0;y<NativeRaw.H;y++){in.readFully(packed);NativeRaw.unpack(packed,10,row);for(int value:row)histogram[value]++;}
        }
        return fromHistogram(histogram,firstNs);
    }
}
