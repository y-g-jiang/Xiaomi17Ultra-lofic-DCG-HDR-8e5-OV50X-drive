package local.jc.mainraw;

import android.graphics.Bitmap;

/** Same-process visual continuity only; this frame is never a capture result. */
final class PairCaptureView {
    static volatile Bitmap lastFrame;
    static Bitmap snapshot(android.view.TextureView texture){
        Bitmap source=texture.getBitmap();if(source==null)return null;
        Bitmap out=Bitmap.createBitmap(source.getWidth(),source.getHeight(),Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(out);canvas.drawColor(android.graphics.Color.BLACK);
        canvas.drawBitmap(source,texture.getTransform(new android.graphics.Matrix()),new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        source.recycle();return out;
    }
}
