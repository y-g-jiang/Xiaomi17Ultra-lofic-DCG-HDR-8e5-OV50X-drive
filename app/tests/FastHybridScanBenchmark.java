package local.jc.mainraw;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

public final class FastHybridScanBenchmark {
    public static void main(String[] args) throws Exception {
        Random random = new Random(42924);
        for (int width=1; width<70; width++) {
            int stride = width*2+10, height=19;
            byte[] data = new byte[stride*height]; random.nextBytes(data);
            for(int i=0;i+1<data.length;i+=6) { data[i]=0;data[i+1]=0; }
            FastHybridFrameCheck full=new FastHybridFrameCheck(data,width,height,stride,2,32);
            FastHybridFrameCheck quick=new FastHybridFrameCheck(data,width,height,stride,2,32,false);
            if(full.nonzeroSamples!=quick.nonzeroSamples || full.nonzeroRows!=quick.nonzeroRows
                    || full.complete!=quick.complete) throw new AssertionError("coverage scan mismatch");
        }
        System.out.println("Coverage scan: 69 padded and odd-width cases matched");
        if(args.length==0) return;
        byte[] real=Files.readAllBytes(Paths.get(args[0]));
        for(int i=0;i<6;i++) {
            boolean extrema=i%2==0; long start=System.nanoTime();
            FastHybridFrameCheck r=new FastHybridFrameCheck(real,8192,6144,16384,2,32,extrema);
            System.out.println("scan extrema="+extrema+" ms="+(System.nanoTime()-start)/1e6
                    +" nonzero="+r.nonzeroSamples+" rows="+r.nonzeroRows+" complete="+r.complete);
        }
    }
}
