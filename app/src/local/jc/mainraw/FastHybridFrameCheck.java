package local.jc.mainraw;

/** Transport integrity only; this does not certify the physical sensor mode. */
final class FastHybridFrameCheck {
    final boolean layoutValid, complete;
    final long nonzeroSamples;
    final int nonzeroRows, minimum, maximum;
    final double nonzeroFraction;

    FastHybridFrameCheck(byte[] data, int width, int height, int rowStride, int pixelStride, int format) {
        this(data, width, height, rowStride, pixelStride, format, true);
    }

    FastHybridFrameCheck(byte[] data, int width, int height, int rowStride, int pixelStride, int format, boolean extrema) {
        layoutValid = format == 32 && width > 0 && height > 0 && pixelStride == 2
                && rowStride >= (long) width * 2 && rowStride % 2 == 0
                && data.length == (long) rowStride * height;
        long count = 0;
        int rows = 0, low = 65535, high = 0;
        if (layoutValid) {
            for (int y = 0; y < height; y++) {
                boolean populated = false;
                int offset = y * rowStride;
                int x = 0;
                if (!extrema) {
                    // All samples are inspected; positive bytes need no ushort decoding.
                    for (; x < width; x++) {
                        int i = offset + x * 2;
                        if (data[i] != 0 || data[i + 1] != 0) { count++; populated = true; }
                    }
                }
                for (; x < width; x++) {
                    int i = offset + x * 2;
                    int value = (data[i] & 255) | ((data[i + 1] & 255) << 8);
                    if (value != 0) { count++; populated = true; }
                    if (extrema) { low = Math.min(low, value); high = Math.max(high, value); }
                }
                if (populated) rows++;
            }
        }
        nonzeroSamples = count; nonzeroRows = rows;
        minimum = layoutValid && extrema ? low : -1; maximum = layoutValid && extrema ? high : -1;
        nonzeroFraction = layoutValid ? count / ((double) width * height) : 0;
        // This experiment has positive black pedestal. Reject empty padding;
        // an unusual legitimately zero-valued scene is retained for inspection.
        complete = layoutValid && nonzeroFraction > 0.9 && rows > 0.98 * height;
    }

    static boolean metadataMatches(long startedTimestamp, long imageTimestamp, long resultTimestamp,
            int actualIso, long actualExposure, double actualFocus, boolean physicalResultPresent) {
        return metadataMatches(startedTimestamp, imageTimestamp, resultTimestamp, actualIso, actualExposure,
                actualFocus, physicalResultPresent, false);
    }

    static boolean metadataMatches(long startedTimestamp, long imageTimestamp, long resultTimestamp,
            int actualIso, long actualExposure, double actualFocus, boolean physicalResultPresent,
            boolean factoryInfinityEvidence) {
        return metadataMatches(startedTimestamp, imageTimestamp, resultTimestamp, actualIso, actualExposure,
                actualFocus, physicalResultPresent, factoryInfinityEvidence, false);
    }

    static boolean metadataMatches(long startedTimestamp, long imageTimestamp, long resultTimestamp,
            int actualIso, long actualExposure, double actualFocus, boolean physicalResultPresent,
            boolean factoryInfinityEvidence, boolean mode0UnityIso70Verified) {
        return startedTimestamp > 0 && startedTimestamp == imageTimestamp
                && imageTimestamp == resultTimestamp && (actualIso == 50 || (actualIso == 70 && mode0UnityIso70Verified)) && actualExposure > 0
                && (actualFocus == 0.0 || factoryInfinityEvidence) && physicalResultPresent;
    }

    static boolean unityMode0(int iso, int mode, float[] aecGain, float[] postSensorGain, int postRawBoost) {
        if (iso != 70 || mode != 0 || aecGain == null || aecGain.length != 3
                || postSensorGain == null || postSensorGain.length != 1 || postSensorGain[0] != 1f || postRawBoost != 100) return false;
        for (float gain : aecGain) if (gain != 1f) return false;
        return true;
    }
}
