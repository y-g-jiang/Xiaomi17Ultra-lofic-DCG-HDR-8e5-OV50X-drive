package local.jc.mainraw;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import android.view.Surface;
/** Preview-only native buffer descriptor probe. No pixel or HDR success claim. */
final class NativeRawSurfaceProbe {
    static { System.loadLibrary("jcrawsurface"); }
    static native void reinitialize(ImageReader reader);
    static native void reinitializeAttached(ImageReader reader);
    static native int[] configure(Surface surface,int width,int height,int format,int dataSpace);
    static native long[] describe(HardwareBuffer hardware);
    static native byte[] copyRaw14(HardwareBuffer hardware);
}
