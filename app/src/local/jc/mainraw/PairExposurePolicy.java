package local.jc.mainraw;

/** Safety shutter policy; sensor line quantization is checked, never rewritten. */
final class PairExposurePolicy {
    static final long SHUTTER_NS = 33_333_333L;
    static final long HDR_LINE_NS = 30_683L;
    static final class Plan {
        final long exposureNs;
        final double gain;
        Plan(long exposure, double gain) { this.exposureNs = exposure; this.gain = gain; }
    }
    static Plan meter(long exposureNs, int iso) {
        if (exposureNs <= 0 || iso <= 0) throw new IllegalArgumentException("Metering unavailable");
        long shutter = Math.min(SHUTTER_NS, exposureNs);
        double gain = Math.max(1, iso / 70.0 * (exposureNs / (double)shutter));
        // Bound the supported raw gain request; saturation is reported as limited AE.
        return new Plan(shutter, Math.min(16, gain));
    }
    static Plan tripod(long exposureNs, int iso) {
        if(exposureNs<=0 || iso<=0)throw new IllegalArgumentException("Metering unavailable");
        return new Plan(Math.max(100_000L,Math.min(NativeEttr.MAX_NS,Math.round(exposureNs*(iso/70.0)))),1);
    }
    static boolean tripod50(long standardNs,long sensorNs) {
        return standardNs>0 && standardNs<=NativeEttr.MAX_NS && sensorNs>0 && sensorNs<=NativeEttr.MAX_NS;
    }
    static boolean hdrMatches(long requested,long actual) {
        return actual>0 && Math.abs(requested-actual)<=HDR_LINE_NS;
    }
    static boolean safe50(long standardNs, long sensorNs) {
        return standardNs > 0 && standardNs <= SHUTTER_NS && sensorNs > 0 && sensorNs <= SHUTTER_NS;
    }
    static boolean fixedHdr(long sensorNs) {
        return sensorNs <= SHUTTER_NS && sensorNs > SHUTTER_NS - HDR_LINE_NS;
    }
}
