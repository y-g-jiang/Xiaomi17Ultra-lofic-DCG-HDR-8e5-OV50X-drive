package local.jc.mainraw;

import java.util.Arrays;

public final class FastHybridPackedRaw10CheckTest {
    static void check(boolean valid) { if (!valid) throw new AssertionError(); }
    public static void main(String[] args) {
        byte[] bytes = {0, 0, 0, (byte)255, (byte)249, 99, 99, 99};
        FastHybridPackedRaw10Check c = new FastHybridPackedRaw10Check(bytes, 4, 1, 8, 0);
        check(c.complete && c.minimum == 1 && c.maximum == 1023 && c.nonzeroSamples == 4);
        byte[] padding = {0, 0, 0, 0, 0, 99, 99, 99};
        check(!new FastHybridPackedRaw10Check(padding, 4, 1, 8, 0).complete);
        byte[] partial = new byte[8 * 16]; Arrays.fill(partial, 0, 8 * 4, (byte)16);
        c = new FastHybridPackedRaw10Check(partial, 4, 16, 8, 0);
        check(!c.complete && c.nonzeroRows == 4 && c.nonzeroSamples == 16);
        check(!new FastHybridPackedRaw10Check(bytes, 4, 2, 8, 0).layoutValid);
        check(!new FastHybridPackedRaw10Check(bytes, 4, 1, 8, 2).layoutValid);
        System.out.println("RAW10 packing, padding, truncation, and partial-row checks passed");
    }
}
