package local.jc.mainraw;

/** Packed RAW10 transport checks only; sensor mode needs independent evidence. */
final class FastHybridPackedRaw10Check {
    final boolean layoutValid, complete;
    final int nonzeroRows, minimum, maximum;
    final long nonzeroSamples;
    FastHybridPackedRaw10Check(byte[] data, int width, int height, int stride, int pixelStride) {
        layoutValid = width > 0 && height > 0 && width % 4 == 0 && pixelStride == 0
                && stride >= width * 5L / 4 && data.length == (long) stride * height;
        long count = 0; int rows = 0, low = 1023, high = 0;
        if (layoutValid) for (int y = 0; y < height; y++) {
            boolean populated = false;
            for (int x = 0; x < width; x += 4) {
                int offset = y * stride + x / 4 * 5;
                int lsb = data[offset + 4] & 255;
                for (int lane = 0; lane < 4; lane++) {
                    int value = ((data[offset + lane] & 255) << 2) | ((lsb >> (2 * lane)) & 3);
                    if (value != 0) { count++; populated = true; }
                    low = Math.min(low, value); high = Math.max(high, value);
                }
            }
            if (populated) rows++;
        }
        nonzeroSamples = count; nonzeroRows = rows;
        minimum = layoutValid ? low : -1; maximum = layoutValid ? high : -1;
        complete = layoutValid && count > 0.9 * width * height && rows > 0.98 * height;
    }
}
