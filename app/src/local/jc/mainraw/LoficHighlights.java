package local.jc.mainraw;

import java.io.*;

/** Full original RAW10 scan. Saturation in a color is not saturation of all colors. */
final class LoficHighlights {
    static final int WHITE=1023,BLACK=64,CHANNEL_PIXELS=4096*3072/4;
    static final class Result {
        final long[] clipped=new long[4];final double[] top10=new double[4];
        long allChannelsClippedCells;int bestChannel;
        double headroomEv;
    }
    static Result fromHistograms(int[][] hist,long allClipped)throws IOException {
        if(hist==null||hist.length!=4||allClipped<0||allClipped>CHANNEL_PIXELS)throw new IOException("Invalid highlight data");
        Result r=new Result();r.allChannelsClippedCells=allClipped;double best=Double.POSITIVE_INFINITY;
        for(int c=0;c<4;c++){
            if(hist[c]==null||hist[c].length!=1024)throw new IOException("Invalid channel histogram");
            long count=0;for(int n:hist[c]){if(n<0)throw new IOException("Negative count");count+=n;}
            if(count!=CHANNEL_PIXELS)throw new IOException("Incomplete channel");
            int need=10;long sum=0;for(int v=WHITE;v>=0&&need>0;v--){int take=Math.min(need,hist[c][v]);sum+=(long)take*v;need-=take;}
            r.top10[c]=sum/10.0;r.clipped[c]=hist[c][WHITE];if(r.top10[c]<best){best=r.top10[c];r.bestChannel=c;}
        }
        r.headroomEv=best>BLACK?Math.log((WHITE-BLACK)/(best-BLACK))/Math.log(2):Double.POSITIVE_INFINITY;return r;
    }
    static Result measure(File raw)throws IOException {
        if(raw.length()!=15728640L)throw new IOException("Incomplete LOFIC RAW10");
        int[][] hist=new int[4][1024];int[] previous=new int[4096],row=new int[4096];byte[] packed=new byte[5120];long all=0;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(raw)))){
            for(int y=0;y<3072;y++){in.readFully(packed);NativeRaw.unpack(packed,10,row);for(int x=0;x<4096;x++)hist[(y&1)*2+(x&1)][row[x]]++;
                if((y&1)==1){for(int x=0;x<4096;x+=2)if(previous[x]==WHITE&&previous[x+1]==WHITE&&row[x]==WHITE&&row[x+1]==WHITE)all++;}
                else System.arraycopy(row,0,previous,0,4096);
            }
            if(in.read()!=-1)throw new IOException("Trailing RAW data");
        }
        return fromHistograms(hist,all);
    }
}
