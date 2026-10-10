package local.jc.mainraw;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;

/** Minimal on-phone native capture -> per-timepoint DNG workflow. */
public final class NativePhotoActivity extends Activity {
    private TextView status;
    private EditText exposure;
    private Switch infinityFocus,ettr,highlightUnity;
    private Button prepare,export,recover,reference,rootCheck;
    private NativePhotoBridge bridge;
    private volatile boolean busy;
    private boolean leavingForCamera;
    interface Work {void run()throws Exception;}
    private CameraDashboard dashboard;
    private FixedIsoPreview livePreview;
    private boolean previewWanted,resumed;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dashboard=new CameraDashboard(this);
        status=dashboard.status;exposure=dashboard.exposure;infinityFocus=dashboard.infinity;
        ettr=dashboard.ettr;highlightUnity=dashboard.unity;prepare=dashboard.capture;
        export=dashboard.export;recover=dashboard.recover;reference=dashboard.advanced;rootCheck=dashboard.rootCheck;
        infinityFocus.setChecked(getSharedPreferences("capture_ui",0).getBoolean("infinity",true));
        highlightUnity.setChecked(getSharedPreferences("capture_ui",0).getBoolean("highlightUnity",true));
        ettr.setChecked(getSharedPreferences("capture_ui",0).getBoolean("ettr",false));
        infinityFocus.setOnCheckedChangeListener((v,on)->{getSharedPreferences("capture_ui",0).edit().putBoolean("infinity",on).apply();previewControls();});
        highlightUnity.setOnCheckedChangeListener((v,on)->getSharedPreferences("capture_ui",0).edit().putBoolean("highlightUnity",on).apply());
        ettr.setOnCheckedChangeListener((v,on)->getSharedPreferences("capture_ui",0).edit().putBoolean("ettr",on).apply());
        livePreview=new FixedIsoPreview(this,dashboard.preview,(ns,iso,h,message)->{
            dashboard.telemetry.setText(String.format(java.util.Locale.US,"ISO %s   ·   %.3f ms   ·   主摄 1×",iso==0?"—":Integer.toString(iso),ns/1e6));
            if(h!=null)dashboard.histogram.show(h);
            if(iso==50&&dashboard.auto.isChecked())exposure.setText(String.format(java.util.Locale.US,"%.6f",ns/1e6));
            if(h==null&&message!=null)dashboard.telemetry.setText(message);
        });
        dashboard.auto.setOnCheckedChangeListener((v,on)->{exposure.setEnabled(!on&&!busy);previewControls();});
        exposure.setEnabled(false);
        exposure.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence t,int st,int count,int after){}
            public void onTextChanged(CharSequence t,int st,int before,int count){if(!dashboard.auto.isChecked())previewControls();}
            public void afterTextChanged(android.text.Editable t){}
        });
        dashboard.preview.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture t,int w,int h){transform();startPreviewIfSafe();}
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture t,int w,int h){transform();}
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture t){livePreview.stop();return true;}
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture t){}
        });
        dashboard.previewButton.setOnClickListener(v->{
            if(busy||NativePhotoBridge.WORKING.get())return;
            previewWanted=!previewWanted;dashboard.previewButton.setText(previewWanted?"关闭实时预览":"开启实时预览");
            if(previewWanted&&checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.CAMERA},17);
            else if(previewWanted)startPreviewIfSafe();else livePreview.stop();
        });
        prepare.setOnClickListener(v->{if(!busy)prepare();});
        dashboard.ptc.setOnClickListener(v->{if(!busy&&!NativePhotoBridge.WORKING.get()){livePreview.stop();startActivity(new Intent(this,PtcActivity.class));}});
        reference.setOnClickListener(v->{if(!busy&&!NativePhotoBridge.WORKING.get()&&bridge!=null&&bridge.session()==null){livePreview.stop();startActivity(new Intent(this,MainActivity.class));}});
        rootCheck.setOnClickListener(v->work(()->say(bridge.checkRoot())));
        export.setOnClickListener(v->export());
        recover.setOnClickListener(v->work(()->{bridge.recover();say("已恢复，原始文件保留。");}));
        try{bridge=new NativePhotoBridge(this);if(bridge.session()!=null)say("有待处理拍摄，可导出或恢复。");}
        catch(Exception e){say(e.toString());prepare.setEnabled(false);}
    }
    private void previewControls(){if(livePreview==null)return;try{double ms=Double.parseDouble(exposure.getText().toString());if(Double.isFinite(ms)&&ms>0)livePreview.controls(dashboard.auto.isChecked(),infinityFocus.isChecked(),Math.round(ms*1e6));}catch(Exception ignored){}}
    private void startPreviewIfSafe(){if(previewWanted&&resumed&&!busy&&!NativePhotoBridge.WORKING.get()&&bridge!=null&&bridge.session()==null){previewControls();livePreview.start();}}
    private void transform(){if(dashboard.preview.getSurfaceTexture()==null)return;float w=dashboard.preview.getWidth(),h=dashboard.preview.getHeight();android.graphics.Matrix m=new android.graphics.Matrix();android.graphics.RectF view=new android.graphics.RectF(0,0,w,h),buffer=new android.graphics.RectF(0,0,960,1280);buffer.offset(view.centerX()-buffer.centerX(),view.centerY()-buffer.centerY());m.setRectToRect(view,buffer,android.graphics.Matrix.ScaleToFit.FILL);m.postRotate(90,view.centerX(),view.centerY());dashboard.preview.setTransform(m);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==17&&g.length>0&&g[0]==0)startPreviewIfSafe();}
    @Override protected void onPause(){resumed=false;if(livePreview!=null)livePreview.stop();super.onPause();}
    @Override protected void onDestroy(){if(livePreview!=null)livePreview.destroy();super.onDestroy();}
    private TextView text(LinearLayout l,String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextIsSelectable(true);t.setPadding(0,12,0,12);l.addView(t);return t;}
    private Button button(LinearLayout l,String name,Runnable action){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setOnClickListener(v->{if(!busy)action.run();});l.addView(b);return b;}
    private void say(String message){runOnUiThread(()->status.setText(message));}
    private void work(Work action){if(busy||bridge==null)return;if(!NativePhotoBridge.WORKING.compareAndSet(false,true)){say("已有原生采集操作正在执行，请稍候。");return;}busy=true;buttons(false);new Thread(()->{try{livePreview.stopAndWait();action.run();}catch(Exception e){String message="未完成："+e.getMessage();try{bridge.recover();message+="\n已恢复，未把失败拍摄记为成功。";}catch(Exception restore){message+="\n恢复尚未完成："+restore.getMessage();}say(message);}finally{busy=false;NativePhotoBridge.WORKING.set(false);runOnUiThread(()->buttons(true));}},"JCNativeBridge").start();}
    private void buttons(boolean enabled){prepare.setEnabled(enabled);export.setEnabled(enabled);recover.setEnabled(enabled);reference.setEnabled(enabled);rootCheck.setEnabled(enabled);exposure.setEnabled(enabled&&!dashboard.auto.isChecked());dashboard.auto.setEnabled(enabled);dashboard.previewButton.setEnabled(enabled);dashboard.ptc.setEnabled(enabled);infinityFocus.setEnabled(enabled);ettr.setEnabled(enabled);highlightUnity.setEnabled(enabled);}
    private void prepare(){final double ms;try{ms=Double.parseDouble(exposure.getText().toString());}catch(Exception e){say("请输入有效曝光时间。");return;}
        final boolean fixedInfinity=infinityFocus.isChecked(),useEttr=ettr.isChecked(),useUnity=highlightUnity.isChecked();
        say("正在准备 ISO 50 单组采集，请保持手机不动…");work(()->{
            startForegroundService(new Intent(this,CaptureService.class));
            try {
                org.json.JSONObject metering=null;double finalMs=ms;
                if(useEttr){
                    say("第1/2张：读取 LOFIC 最亮10像素…");
                    bridge.prepare(NativeEttr.FIRST_NS/1e6,fixedInfinity,useUnity);capturePrepared();
                    metering=bridge.meterFirstShot();finalMs=metering.getLong("secondRequestedExposureNs")/1e6;
                    say("第2/2张：快门 "+finalMs+" ms，ISO50；拍完直接保存。");
                }
                bridge.prepare(finalMs,fixedInfinity,useUnity);
                if(metering!=null)bridge.setEttrReport(metering);
                capturePrepared();
                int n=bridge.export(this::say);bridge.showApp();
                say((useEttr?"两次拍摄完成，最终快门 "+finalMs+" ms。\n":"")+"已保存 "+n+" 张 DNG"+(n==2?"：DCG RAW14 与 LOFIC 1× RAW10 原始双路。":"：RAW14、RAW10、旧同帧 HDR16 实验版。")+"ISO50 / 模式5 / 四份元数据配对校验通过。\n下载/JCCamera/Native_ISO50_会话编号");
            } catch(Exception e) {try{bridge.showApp();}catch(Exception ignored){}throw e;} finally {stopService(new Intent(this,CaptureService.class));}
        });
    }
    private void capturePrepared()throws Exception {
        bridge.showApp();
        java.util.concurrent.CountDownLatch opened=new java.util.concurrent.CountDownLatch(1);
        final Exception[] failure=new Exception[1];
        runOnUiThread(()->{try{Intent i=new Intent();i.setComponent(new ComponentName("com.android.camera","com.android.camera.Camera"));startActivity(i);}catch(Exception e){failure[0]=e;}finally{opened.countDown();}});
        if(!opened.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("打开相机超时");
        if(failure[0]!=null)throw failure[0];
        bridge.captureAndWait();
    }
    private void export(){work(()->{int n=bridge.export(this::say);say("已保存 "+n+" 张 DNG。每组独立、无时域融合；ISO50 校验通过。临时采集设置已恢复。\n位置：下载/JCCamera/Native_ISO50_会话编号");});}
    @Override protected void onResume(){super.onResume();resumed=true;startPreviewIfSafe();}
}
