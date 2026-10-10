package local.jc.mainraw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** ROM-specific applied sensor data, not the requested AEC exposure array. */
final class FastHybridSensorMetadata {
    static final String TAG = "xiaomi.hdr.stg.exposureInfo";
    static final String CORE_SHA = "b910691b3b010662faaf501544fca36a40ae206a5c66a99a1d77ea36a10789c9";
    final float[] analog = new float[3], digital = new float[3], isp = new float[3];
    final long[] exposureNs = new long[3];
    final float cgRatio, loficRatio;

    FastHybridSensorMetadata(byte[] data) {
        if (data == null || data.length != 80) throw new IllegalArgumentException("Expected 80-byte sensor tag");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 3; i++) {
            int at = i * 24;
            analog[i] = b.getFloat(at); digital[i] = b.getFloat(at + 4); isp[i] = b.getFloat(at + 8);
            exposureNs[i] = b.getLong(at + 16);
            if (!finiteNonnegative(analog[i]) || !finiteNonnegative(digital[i]) || !finiteNonnegative(isp[i])
                    || exposureNs[i] < 0) throw new IllegalArgumentException("Invalid sensor exposure entry");
        }
        cgRatio = b.getFloat(72); loficRatio = b.getFloat(76);
        if (!Float.isFinite(cgRatio) || !Float.isFinite(loficRatio) || cgRatio <= 0 || loficRatio <= 0)
            throw new IllegalArgumentException("Invalid sensor ratios");
    }
    private static boolean finiteNonnegative(float v) { return Float.isFinite(v) && v >= 0; }
    boolean isEqualExposureUnityMode5(int mode, int boost, long frameDurationNs) {
        return isEqualExposureUnityMode5(mode,boost,frameDurationNs,0);
    }
    boolean isEqualExposureUnityMode5(int mode, int boost, long frameDurationNs, long roundingToleranceNs) {
        if(roundingToleranceNs<0||roundingToleranceNs>60)return false;
        if (mode != 5 || boost != 100 || exposureNs[0] <= 0 || Math.abs(exposureNs[2]-exposureNs[0])>roundingToleranceNs
                || frameDurationNs < exposureNs[0]) return false;
        for (int i : new int[]{0, 2})
            if (analog[i] != 1f || digital[i] != 1f || isp[i] != 1f) return false;
        // Mode 5 publishes no separate middle exposure. Never invent one.
        return analog[1] == 0f && digital[1] == 0f && isp[1] == 0f && exposureNs[1] == 0;
    }
    static boolean matches(long started, long image, long result, int standardIso, long standardExposure,
            double focus, boolean physical, boolean infinity, boolean verifiedDriverData) {
        return verifiedDriverData && standardIso == 0 && standardExposure == 0
                && started > 0 && started == image && image == result
                && physical && (focus == 0.0 || infinity);
    }
}
