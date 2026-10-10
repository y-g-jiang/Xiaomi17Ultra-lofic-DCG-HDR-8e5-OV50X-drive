package local.jc.mainraw;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class FastHybridPipelineTest {
    private static int checks;
    private static void check(boolean b, String name) {
        if (!b) throw new AssertionError(name); checks++;
    }
    public static void main(String[] args) throws Exception {
        FastHybridFrameLifecycle a = new FastHybridFrameLifecycle();
        check(!a.validate() && !a.canHandoff(), "unready");
        check(a.markReady(true) && a.canHandoff() && !a.validate(), "handoff before write");
        check(!a.markReady(true), "no duplicate handoff");
        FastHybridFrameLifecycle b = new FastHybridFrameLifecycle();
        check(b.markReady(true), "second frame while first writing");
        a.markPersisted(); check(a.validate(), "old generation finalizes");
        check(!b.validate(), "pair not persisted");
        b.reject(); b.markPersisted();
        check(!b.validate() && !b.canHandoff(), "late disk failure invalidates");
        FastHybridFrameLifecycle bad = new FastHybridFrameLifecycle();
        check(!bad.markReady(false) && !bad.canHandoff(), "partial buffer never advances");
        FastHybridFrameLifecycle early = new FastHybridFrameLifecycle();
        early.markPersisted(); check(!early.validate(), "save before metadata is not success");
        early.markReady(true); check(early.validate(), "out of order join");
        double[] gaps = FastHybridFrameLifecycle.intervals(1000000000L, 1000000L, 1100000000L, 15000000L);
        check(gaps[0] == 100 && gaps[1] == 107 && gaps[2] == 99, "real exposure timing");
        boolean invalid = false;
        try { FastHybridFrameLifecycle.intervals(1, 0, 2, 1); } catch (IllegalArgumentException e) { invalid=true; }
        check(invalid, "reject missing exposure");
        String log = new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8");
        check(FastHybridFocusEvidence.matches(log, 1790657060000L), "real calibrated infinity evidence");
        check(!FastHybridFocusEvidence.matches(log, 1890657060000L), "stale actuator evidence");
        check(!FastHybridFocusEvidence.matches(log.replace("824", "339"), 0), "old near focus not infinity");
        check(!FastHybridFocusEvidence.matches(log.replace("DAC:552", "DAC:2412"), 0), "wrong DAC");
        check(FastHybridFrameCheck.metadataMatches(1, 1, 1, 50, 1000, .05, true, true), "corroborated factory override");
        check(!FastHybridFrameCheck.metadataMatches(1, 1, 1, 50, 1000, .05, true, false), "no focus tolerance loophole");
        float[] unity = {1,1,1}, post = {1};
        check(FastHybridFocusEvidence.isMainRoute("2", null), "direct main route");
        check(FastHybridFocusEvidence.isMainRoute("0", "2"), "explicit physical main route");
        check(!FastHybridFocusEvidence.isMainRoute("0", null), "unbound logical camera rejected");
        check(!FastHybridFocusEvidence.isMainRoute("0", "3"), "other physical sensor rejected");
        check(!FastHybridFocusEvidence.isMainRoute("3", null), "other direct sensor rejected");
        check(FastHybridFrameCheck.unityMode0(70,0,unity,post,100), "authorized ISO70 unity mode0");
        check(!FastHybridFrameCheck.unityMode0(70,5,unity,post,100), "ISO70 excluded from LOFIC");
        check(!FastHybridFrameCheck.unityMode0(70,0,new float[]{1,1,1.4f},post,100), "nonunity gain rejected");
        check(!FastHybridFrameCheck.unityMode0(70,0,unity,post,140), "post RAW gain rejected");
        check(!FastHybridFrameCheck.metadataMatches(1,1,1,70,1000,0,true,false,false), "ISO70 requires evidence");
        check(FastHybridFrameCheck.metadataMatches(1,1,1,70,1000,0,true,false,true), "ISO70 with evidence");
        System.out.println("FastHybridPipeline: " + checks + " checks passed");
    }
}
