package local.jc.mainraw;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** A tap target marker, colored only from actual AF state. */
final class PairFocusOverlay extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x,y;private long until;private int state=-1;
    PairFocusOverlay(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void show(float x,float y){this.x=x;this.y=y;until=android.os.SystemClock.elapsedRealtime()+8000;state=-1;invalidate();postInvalidateDelayed(8010);}
    void hide(){until=0;state=-1;invalidate();}
    void state(int state){this.state=state;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        if(android.os.SystemClock.elapsedRealtime()>until)return;
        float density=getResources().getDisplayMetrics().density,r=32*density,length=12*density;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*density);
        paint.setColor(state==2||state==4?0xff66dd99:state==5||state==6?0xffff7777:0xffffd66b);
        for(int dx:new int[]{-1,1})for(int dy:new int[]{-1,1}){
            canvas.drawLine(x+dx*r,y+dy*r,x+dx*(r-length),y+dy*r,paint);
            canvas.drawLine(x+dx*r,y+dy*r,x+dx*r,y+dy*(r-length),paint);
        }
    }
}
