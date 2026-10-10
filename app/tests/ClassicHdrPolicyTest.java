package local.jc.mainraw;
import java.io.*;
public final class ClassicHdrPolicyTest {
    static void check(boolean c){if(!c)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        check(ShutterDialPolicy.exposure(-1)==100000);check(ShutterDialPolicy.exposure(100)==1000000000);
        check(ShutterDialPolicy.exposure(ShutterDialPolicy.nearest(33333333))==33333333);
        for(int i=0;i<ShutterDialPolicy.count();i++){
            check(ShutterDialPolicy.nearest(ShutterDialPolicy.exposure(i))==i);
            if(i>0)check(ShutterDialPolicy.exposure(i)>ShutterDialPolicy.exposure(i-1));
        }
        int[][] h=new int[4][1024];for(int i=0;i<4;i++)h[i][200+i*100]=LoficHighlights.CHANNEL_PIXELS;
        h[3][500]-=20;h[3][1023]=20;
        LoficHighlights.Result r=LoficHighlights.fromHistograms(h,0);
        check(r.clipped[3]==20&&r.clipped[0]==0&&r.bestChannel==0&&r.top10[0]==200&&r.top10[3]==1023&&r.headroomEv>2);
        for(int i=0;i<4;i++){h[i]=new int[1024];h[i][1023]=LoficHighlights.CHANNEL_PIXELS;}
        r=LoficHighlights.fromHistograms(h,LoficHighlights.CHANNEL_PIXELS);check(r.headroomEv==0&&r.allChannelsClippedCells==LoficHighlights.CHANNEL_PIXELS);
        h[0][1023]--;boolean rejected=false;try{LoficHighlights.fromHistograms(h,0);}catch(IOException e){rejected=true;}check(rejected);
        if(args.length>0){File f=new File(args[0]);byte[] row=new byte[5120];
            // Four clipped channels at different 2x2 positions must not become one lost-color cell.
            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(f))){for(int y=0;y<3072;y++){
                java.util.Arrays.fill(row,(byte)0);for(int x=0;x<4096;x+=4){int off=x/4*5;for(int k=0;k<4;k++)row[off+k]=16;}
                if(y<2){int x=y==0?0:4;int off=x/4*5;row[off]=(byte)255;row[off+1]=(byte)255;row[off+4]=15;}out.write(row);
            }}
            r=LoficHighlights.measure(f);check(r.allChannelsClippedCells==0);for(long n:r.clipped)check(n==1);
            check(r.top10[0]==159.9&&r.bestChannel==0);
        }
        System.out.println("Classic HDR dial and original RAW highlight tests passed");
    }
}
