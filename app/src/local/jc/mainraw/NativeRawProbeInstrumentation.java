package local.jc.mainraw;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
/** Shell-invoked diagnostic; an explicit snapshot probe permits one gated still. */
public final class NativeRawProbeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments=args;android.util.Log.i("JCNativeProbe","instrumentation created");start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if (arguments.getBoolean("readerAudit", false) || "true".equals(arguments.getString("readerAudit"))) {
                StringBuilder methods = new StringBuilder();
                for (java.lang.reflect.Method method : android.media.ImageReader.class.getDeclaredMethods())
                    if (method.getName().contains("nitialize") || method.getName().equals("nativeInit"))
                        methods.append(method.toString()).append('\n');
                result.putString("readerMethods", methods.toString());
                try (android.media.ImageReader reader = new android.media.ImageReader.Builder(4096, 3072)
                        .setMaxImages(3).setUsage(1048579L)
                        .setDefaultHardwareBufferFormat(324).setDefaultDataSpace(146931712).build()) {
                    result.putString("reader", "format=" + reader.getImageFormat() + " hardware="
                            + reader.getHardwareBufferFormat() + " dataspace=" + reader.getDataSpace());
                    result.putString("geometry", java.util.Arrays.toString(NativeRawSurfaceProbe.configure(
                            reader.getSurface(), 4096, 3072, 324, 146931712)));
                } catch (Throwable error) { result.putString("readerError", error.toString()); }
                result.putString("scope", "buffer construction only; no camera opened");
                finish(0, result); return;
            }
            Intent intent=Intent.parseUri(arguments.getString("intentUri"),Intent.URI_INTENT_SCHEME);
            if(intent.getComponent()==null
                    || !"local.jc.mainraw.FastHybridActivity".equals(intent.getComponent().getClassName())
                    || !"local.jc.mainraw".equals(intent.getComponent().getPackageName())
                    || !intent.getBooleanExtra("previewOnly",false)
                    || !intent.getBooleanExtra("nativeRawSurfaceProbe",false))
                throw new IllegalArgumentException("Only explicit bounded native diagnostics are permitted");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // The shell launches the explicit Activity after this marker.
            // A context launch is blocked by this ROM's background policy.
            android.util.Log.i("JCNativeProbe","instrumented preview process ready");
            Thread.sleep(20000);
            result.putString("scope", intent.getBooleanExtra("nativeSnapshotProbe", false)
                    ? "bounded single snapshot diagnostic; inspect journal for actual result"
                    : "preview diagnostic; inspect journal for actual result");
            finish(0,result);
        } catch(Exception e) { android.util.Log.e("JCNativeProbe","probe launch failed",e);result.putString("error",e.toString());finish(1,result); }
    }
}
