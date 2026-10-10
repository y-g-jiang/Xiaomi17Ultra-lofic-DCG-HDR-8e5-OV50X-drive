package local.jc.mainraw;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;

/** Drag a marked dial; every new stop immediately updates the camera request. */
final class ShutterDial extends View {
    interface Listener {void changed(long ns);}
    private final Paint p=new Paint(3);private final float density;
    private int index=ShutterDialPolicy.nearest(33333333);private float down;private int start;
    private Listener listener;
    ShutterDial(Context c){super(c);density=getResources().getDisplayMetrics().density;setFocusable(true);describe();}
    void listener(Listener l){listener=l;}
    void exposure(long ns){index=ShutterDialPolicy.nearest(ns);describe();invalidate();}
    long exposure(){return ShutterDialPolicy.exposure(index);}
    private void describe(){setContentDescription("快门拨盘 "+ShutterDialPolicy.label(index));}
    private void select(int value){value=Math.max(0,Math.min(ShutterDialPolicy.count()-1,value));if(value==index)return;index=value;describe();invalidate();if(listener!=null)listener.changed(exposure());}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float center=getWidth()/2f,spacing=30*density;
        c.drawColor(0xff161b23);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(13*density);
        for(int i=0;i<ShutterDialPolicy.count();i++){float x=center+(i-index)*spacing;if(x<0||x>getWidth())continue;
            p.setColor(i==index?0xff79e4c7:0xff8995a5);p.setStrokeWidth((i==index?3:1)*density);
            c.drawLine(x,12*density,x,(i%3==0?37:29)*density,p);
            if(i%3==0&&Math.abs(i-index)>1)c.drawText(ShutterDialPolicy.label(i).replace(" s",""),x,57*density,p);
        }
        p.setColor(0xff79e4c7);p.setTextSize(19*density);c.drawText(ShutterDialPolicy.label(index),center,81*density,p);
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(!isEnabled())return false;
        if(e.getAction()==MotionEvent.ACTION_DOWN){down=e.getX();start=index;getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(e.getAction()==MotionEvent.ACTION_MOVE){select(start+Math.round((down-e.getX())/(30*density)));return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;}
        if(e.getAction()==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int k,KeyEvent e){if(isEnabled()&&(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT)){select(index+(k==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1));return true;}return super.onKeyDown(k,e);}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo n){super.onInitializeAccessibilityNodeInfo(n);n.setClassName("android.widget.SeekBar");n.setScrollable(true);n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    @Override public boolean performAccessibilityAction(int action,Bundle args){if(isEnabled()&&(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){select(index+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1));return true;}return super.performAccessibilityAction(action,args);}
}
