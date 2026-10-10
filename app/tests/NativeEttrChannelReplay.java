package local.jc.mainraw;
import java.io.File;
import java.util.Arrays;
public final class NativeEttrChannelReplay {
    public static void main(String[] args)throws Exception {
        NativeEttr.Result result=NativeEttr.measureChannels(new File(args[0]),Long.parseLong(args[1]));
        System.out.println(Arrays.toString(result.channelMeans));
        System.out.println(NativeEttr.CHANNELS[result.selectedChannel]+" "+result.mean+" "+result.exposureNs);
    }
}
