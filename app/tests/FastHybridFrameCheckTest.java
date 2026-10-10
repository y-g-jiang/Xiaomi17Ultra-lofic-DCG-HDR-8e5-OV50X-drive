package local.jc.mainraw;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public final class FastHybridFrameCheckTest {
    private static int checks;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        byte[] valid = new byte[32 * 16 * 2];
        Arrays.fill(valid, (byte) 1);
        check(new FastHybridFrameCheck(valid, 32, 16, 64, 2, 32).complete, "complete positive control");
        byte[] quarter = valid.clone();
        Arrays.fill(quarter, quarter.length / 4, quarter.length, (byte) 0);
        FastHybridFrameCheck partial = new FastHybridFrameCheck(quarter, 32, 16, 64, 2, 32);
        check(!partial.complete && partial.nonzeroFraction == 0.25 && partial.nonzeroRows == 4, "quarter payload");
        FastHybridFrameCheck quickPartial = new FastHybridFrameCheck(quarter, 32, 16, 64, 2, 32, false);
        check(!quickPartial.complete && quickPartial.nonzeroSamples == partial.nonzeroSamples
                && quickPartial.nonzeroRows == partial.nonzeroRows, "coverage-only partial gate");
        byte[] padding = new byte[80 * 16];
        for (int y = 0; y < 16; y++) Arrays.fill(padding, y * 80 + 64, y * 80 + 80, (byte) 1);
        check(!new FastHybridFrameCheck(padding, 32, 16, 80, 2, 32).complete, "stride padding is not pixels");
        check(!new FastHybridFrameCheck(padding, 32, 16, 80, 2, 32, false).complete, "coverage-only excludes padding");
        byte[] highByte = new byte[32 * 16 * 2];
        for (int i=1;i<highByte.length;i+=2) highByte[i]=(byte)128;
        check(new FastHybridFrameCheck(highByte, 32, 16, 64, 2, 32, false).complete, "nonzero high byte");
        check(!new FastHybridFrameCheck(valid, 32, 16, 64, 1, 32).complete, "bad pixel stride");
        check(!new FastHybridFrameCheck(valid, 32, 16, 62, 2, 32).complete, "bad row stride");
        check(!new FastHybridFrameCheck(valid, 32, 16, 64, 2, 37).complete, "unsupported format");
        check(FastHybridFrameCheck.metadataMatches(123, 123, 123, 50, 1000, 0, true), "matching metadata");
        check(!FastHybridFrameCheck.metadataMatches(123, 124, 123, 50, 1000, 0, true), "cross-frame join");
        check(!FastHybridFrameCheck.metadataMatches(0, 0, 0, 50, 1000, 0, true), "missing timestamps");
        check(!FastHybridFrameCheck.metadataMatches(123, 123, 123, 100, 1000, 0, true), "wrong ISO");
        check(!FastHybridFrameCheck.metadataMatches(123, 123, 123, 50, 0, 0, true), "invalid exposure");
        check(!FastHybridFrameCheck.metadataMatches(123, 123, 123, 50, 1000, Double.NaN, true), "missing focus");
        check(!FastHybridFrameCheck.metadataMatches(123, 123, 123, 50, 1000, 0, false), "missing physical result");
        if (args.length > 0) {
            byte[] historical = Files.readAllBytes(Paths.get(args[0]));
            FastHybridFrameCheck replay = new FastHybridFrameCheck(historical, 8192, 6144, 16384, 2, 32);
            check(!replay.complete && replay.nonzeroFraction == 0.25 && replay.nonzeroRows == 1536, "real invalid RAW replay");
            FastHybridFrameCheck quick = new FastHybridFrameCheck(historical, 8192, 6144, 16384, 2, 32, false);
            check(!quick.complete && quick.nonzeroSamples == replay.nonzeroSamples
                    && quick.nonzeroRows == replay.nonzeroRows, "real invalid RAW coverage-only replay");
        }
        System.out.println("FastHybridFrameCheck: " + checks + " checks passed");
    }
}
