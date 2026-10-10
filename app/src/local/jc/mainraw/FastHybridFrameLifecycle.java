package local.jc.mainraw;

/** Worker-thread state: readiness permits handoff; persistence permits success. */
final class FastHybridFrameLifecycle {
    boolean ready, persisted, failed, validated;
    boolean markReady(boolean valid) {
        if (failed || ready) return false;
        if (!valid) { failed = true; return false; }
        ready = true; return true;
    }
    boolean markPersisted() {
        if (failed || persisted) return false;
        persisted = true; return true;
    }
    boolean validate() {
        if (failed || validated || !ready || !persisted) return false;
        validated = true; return true;
    }
    void reject() { failed = true; validated = false; }
    boolean canHandoff() { return ready && !failed; }
    boolean finished() { return validated || failed; }
    static double[] intervals(long a, long ae, long b, long be) {
        if (a <= 0 || b <= a || ae <= 0 || be <= 0) throw new IllegalArgumentException();
        return new double[]{(b-a)/1e6, ((b-a)+(be-ae)/2.0)/1e6, ((b-a)-ae)/1e6};
    }
}
