package local.jc.mainraw;
import java.io.*;

/** One calculation from the first RAW10; never schedules retries. */
public final class NativeEttr {
    public static final int BLACK=64,WHITE=1023,TOP=10;
    public static final long MIN_NS=23_821L,MAX_NS=1_000_000_000L;
    public static final long FIRST_NS=245452L; // ceil(8 mode5 line periods)
    public static final double LINE_NS=30681.405566;
    public static final String CHANNEL_RULE="minimum_cfa_channel_top10_v1";
    public static final String[] CHANNELS={"B","Gb","Gr","R"};
    public static final class Result {
        public double mean,multiplier;
        public long exposureNs;
        public boolean limited;
        public double[] channelMeans;
        public int selectedChannel=-1;
    }
    public static Result fromHistogram(int[] histogram,long firstNs)throws IOException {
        return fromHistogram(histogram,firstNs,false);
    }
    public static Result fromHistogram(int[] histogram,long firstNs,boolean unityLofic)throws IOException {
        if(histogram.length!=1024||firstNs<MIN_NS||firstNs>MAX_NS)throw new IOException("Invalid ETTR input");
        int remaining=TOP;long sum=0;
        for(int value=1023;value>=0&&remaining>0;value--){
            if(histogram[value]<0)throw new IOException("Invalid histogram");
            int take=Math.min(remaining,histogram[value]);sum+=(long)take*value;remaining-=take;
        }
        if(remaining!=0)throw new IOException("Fewer than 10 RAW pixels");
        Result r=new Result();r.mean=sum/(double)TOP;
        r.multiplier=r.mean>BLACK?(WHITE-BLACK)/(r.mean-BLACK):Double.POSITIVE_INFINITY;
        // Metadata can already be rounded up by <1 ns. A clipped first frame
        // must keep exactly its exposure, not round it into another sensor line.
        if(r.mean==WHITE){r.exposureNs=firstNs;r.limited=false;return r;}
        double offset=unityLofic?0.76955*LINE_NS:0;
        double target=(firstNs+offset)*r.multiplier-offset;
        r.exposureNs=Math.max(MIN_NS,Math.min(MAX_NS,(long)Math.ceil(Math.ceil(target/LINE_NS)*LINE_NS)));
        r.limited=target<MIN_NS||target>MAX_NS;
        return r;
    }
    public static int[] histogram(byte[] pixels)throws IOException {
        if(pixels.length!=15728640)throw new IOException("Incomplete LOFIC RAW10");
        int[] hist=new int[1024];
        for(int p=0;p<pixels.length;p+=5)for(int k=0;k<4;k++)
            hist[((pixels[p+k]&255)<<2)|(((pixels[p+4]&255)>>(k*2))&3)]++;
        return hist;
    }
    public static int[][] channelHistograms(byte[] pixels)throws IOException {
        if(pixels.length!=15728640)throw new IOException("Incomplete LOFIC RAW10");
        int[][] hist=new int[4][1024];
        for(int y=0;y<3072;y++)for(int group=0;group<1024;group++){
            int p=y*5120+group*5;
            for(int k=0;k<4;k++){
                int value=((pixels[p+k]&255)<<2)|(((pixels[p+4]&255)>>(k*2))&3);
                hist[(y&1)*2+(k&1)][value]++;
            }
        }
        return hist;
    }
    public static Result fromChannels(int[][] hist,long firstNs)throws IOException {
        if(hist==null||hist.length!=4)throw new IOException("Expected four CFA channels");
        Result selected=null;double[] means=new double[4];int index=-1;
        for(int c=0;c<4;c++){
            Result r=fromHistogram(hist[c],firstNs,true);means[c]=r.mean;
            if(selected==null||r.mean<selected.mean){selected=r;index=c;}
        }
        selected.channelMeans=means;selected.selectedChannel=index;
        return selected;
    }
    public static Result measureChannels(File raw10,long firstNs)throws IOException {
        if(raw10.length()!=15728640L)throw new IOException("Incomplete LOFIC RAW10");
        byte[] pixels=new byte[15728640];
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(raw10)))){in.readFully(pixels);}
        return fromChannels(channelHistograms(pixels),firstNs);
    }
    public static Result measure(File raw10,long firstNs)throws IOException {
        return measure(raw10,firstNs,false);
    }
    public static Result measure(File raw10,long firstNs,boolean unityLofic)throws IOException {
        if(raw10.length()!=15728640L)throw new IOException("Incomplete LOFIC RAW10");
        int[] histogram=new int[1024],row=new int[NativeRaw.W];byte[] packed=new byte[5120];
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(raw10)))){
            for(int y=0;y<NativeRaw.H;y++){in.readFully(packed);NativeRaw.unpack(packed,10,row);for(int value:row)histogram[value]++;}
        }
        return fromHistogram(histogram,firstNs,unityLofic);
    }
}
