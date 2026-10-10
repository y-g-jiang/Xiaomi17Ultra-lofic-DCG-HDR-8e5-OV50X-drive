package local.jc.mainraw;
import java.io.File;
public final class Native50FinalizeMain {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("folder required");
        File folder=new File(args[0]);PairCaptureStore.atomic(new File(folder,"complete.json"),Native50Store.pair(folder));System.out.println("PAIR_VALID");
    }
}
