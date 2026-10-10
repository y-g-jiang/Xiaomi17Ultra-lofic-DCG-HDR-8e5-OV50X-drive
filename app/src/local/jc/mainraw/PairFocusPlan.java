package local.jc.mainraw;
import org.json.JSONObject;
/** Locked Camera2 AF result carried into both manual-focus capture requests. */
final class PairFocusPlan {
    final FixedIsoPreview.FocusSample focus;
    private PairFocusPlan(FixedIsoPreview.FocusSample focus){this.focus=focus;}
    static PairFocusPlan read(FixedIsoPreview.FocusSample focus) {
        if(focus.state!=4||!Float.isFinite(focus.distance)||focus.distance<0)throw new IllegalArgumentException("未确认合焦");
        return new PairFocusPlan(focus);
    }
    JSONObject json()throws Exception{return new JSONObject().put("afState",focus.state)
            .put("focusDistance",focus.distance).put("focusLockedAt",focus.wallMs).put("previewSince",focus.previewSince)
            .put("source","three stationary FOCUSED_LOCKED preview results");}
}
