package local.jc.mainraw;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;

/** Minimal on-phone native capture -> per-timepoint DNG workflow. */
public final class NativePhotoActivity extends Activity {
    private TextView status;
    private EditText exposure;
    private Switch infinityFocus,ettr;
    private Button prepare,export,recover,reference,rootCheck;
    private NativePhotoBridge bridge;
    private volatile boolean busy;
    private boolean leavingForCamera;
    interface Work {void run()throws Exception;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        ScrollView scroll=new ScrollView(this);LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(32,32,32,32);scroll.addView(layout);setContentView(scroll);
        text(layout,"JC 主摄 · 同帧 RAW 草稿 0.3.13",24);
        text(layout,"ISO 50 固定 · 主摄 1×\n每个时间点分别保存 RAW14、配对 RAW10、同帧 HDR 16 位整数实验版，共 3 张 DNG。各时间点之间不融合。",17);
        text(layout,"曝光时间（0.023821–1000 ms，默认 3.75 ms；只改快门，不改 ISO）",14);exposure=new EditText(this);exposure.setSingleLine();exposure.setText("3.75");exposure.setInputType(8194);layout.addView(exposure);
        infinityFocus=new Switch(this);infinityFocus.setText("固定无限远对焦");infinityFocus.setTextSize(18);
        infinityFocus.setChecked(getSharedPreferences("capture_ui",0).getBoolean("infinity",false));
        infinityFocus.setOnCheckedChangeListener((b,on)->getSharedPreferences("capture_ui",0).edit().putBoolean("infinity",on).apply());layout.addView(infinityFocus);
        text(layout,"开启：锁定本机主摄的出厂无限远位置后拍摄。关闭：自动对焦。拍摄结束会恢复原设置。",14);
        ettr=new Switch(this);ettr.setText("两张向右曝光 · LOFIC Top10");ettr.setTextSize(18);
        ettr.setChecked(getSharedPreferences("capture_ui",0).getBoolean("ettr",true));
        ettr.setOnCheckedChangeListener((b,on)->getSharedPreferences("capture_ui",0).edit().putBoolean("ettr",on).apply());layout.addView(ettr);
        text(layout,"第一张固定约0.245ms短曝光测光，按最亮10个LOFIC像素均值直接计算第二张快门，只保存第二张的三份DNG。不迭代；第二张最长1秒（设备公开范围）。第一张已截顶时倍率为1。",14);
        rootCheck=button(layout,"检查 Root / 清理未启动会话",()->work(()->say(bridge.checkRoot())));
        prepare=button(layout,"拍摄并自动保存 DNG（ISO 50）",()->prepare());
        button(layout,"PTC 自动曝光扫描（只存成对原始 DNG）",()->startActivity(new Intent(this,PtcActivity.class)));
        text(layout,"需要 KernelSU 授予本应用 Root。原生 Photo V5 临时补丁须已启用。\n会短暂打开原生相机，自动按一次快门并返回导出。保持后置「拍照」1×和自动夜景开启；请勿额外按快门。采集窗口 3 分钟，到时关闭原生相机并恢复临时设置。",14);
        export=button(layout,"2. 导出本次多张 DNG",()->export());
        recover=button(layout,"结束 / 恢复本次采集",()->work(()->{bridge.recover();say("已结束本次采集并恢复设置。已有原始文件保留。");}));
        reference=button(layout,"Camera2 参数草稿（普通 RAW，ISO 同样固定）",()->{if(bridge.session()!=null){say("请先导出或结束原生采集。");return;}startActivity(new Intent(this,MainActivity.class));});
        status=text(layout,"准备就绪。按拍摄按钮自动完成；两张测光已可用。",16);
        text(layout,"保存到 下载/JCCamera/Native_ISO50_会话编号。单组实验配方只请求一个时间点，输出 3 张 DNG。原生 JPEG 后处理不兼容此配方，本应用取得 RAW 后结束原生流程。\n合并后统一缩放 ½ 写入 UInt16；不加伽马，黑电平约512，白电平58052。全范围线性度尚待曝光扫描标定；暗部量化仍在。实际 ISO 不等于 50、模式或配对数据不符时，拒绝输出成功样张。",14);
        try{bridge=new NativePhotoBridge(this);if(bridge.session()!=null){say("发现待处理记录，正在检查 Root 与实际会话状态…");new Handler(Looper.getMainLooper()).post(()->work(()->say(bridge.checkRoot())));}}catch(Exception e){say(e.toString());prepare.setEnabled(false);}
    }
    private TextView text(LinearLayout l,String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextIsSelectable(true);t.setPadding(0,12,0,12);l.addView(t);return t;}
    private Button button(LinearLayout l,String name,Runnable action){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setOnClickListener(v->{if(!busy)action.run();});l.addView(b);return b;}
    private void say(String message){runOnUiThread(()->status.setText(message));}
    private void work(Work action){if(busy||bridge==null)return;if(!NativePhotoBridge.WORKING.compareAndSet(false,true)){say("已有原生采集操作正在执行，请稍候。");return;}busy=true;buttons(false);new Thread(()->{try{action.run();}catch(Exception e){say("未完成："+e.getMessage()+"\n有待处理会话时，可按‘结束 / 恢复本次采集’再重试。");}finally{busy=false;NativePhotoBridge.WORKING.set(false);runOnUiThread(()->buttons(true));}},"JCNativeBridge").start();}
    private void buttons(boolean enabled){prepare.setEnabled(enabled);export.setEnabled(enabled);recover.setEnabled(enabled);reference.setEnabled(enabled);rootCheck.setEnabled(enabled);exposure.setEnabled(enabled);infinityFocus.setEnabled(enabled);ettr.setEnabled(enabled);}
    private void prepare(){final double ms;try{ms=Double.parseDouble(exposure.getText().toString());}catch(Exception e){say("请输入有效曝光时间。");return;}
        final boolean fixedInfinity=infinityFocus.isChecked(),useEttr=ettr.isChecked();
        say("正在准备 ISO 50 单组采集，请保持手机不动…");work(()->{
            startForegroundService(new Intent(this,CaptureService.class));
            try {
                org.json.JSONObject metering=null;double finalMs=ms;
                if(useEttr){
                    say("第1/2张：读取 LOFIC 最亮10像素…");
                    bridge.prepare(NativeEttr.FIRST_NS/1e6,fixedInfinity);capturePrepared();
                    metering=bridge.meterFirstShot();finalMs=metering.getLong("secondRequestedExposureNs")/1e6;
                    say("第2/2张：快门 "+finalMs+" ms，ISO50；拍完直接保存。");
                }
                bridge.prepare(finalMs,fixedInfinity);
                if(metering!=null)bridge.setEttrReport(metering);
                capturePrepared();
                int n=bridge.export(this::say);bridge.showApp();
                say((useEttr?"两次拍摄完成，最终快门 "+finalMs+" ms。\n":"")+"已保存 "+n+" 张 DNG：RAW14、RAW10、同时间点 HDR16。ISO50 / 模式5 / 四份元数据配对校验通过。\n下载/JCCamera/Native_ISO50_会话编号");
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
    @Override protected void onResume(){super.onResume();if(leavingForCamera){leavingForCamera=false;say("拍摄保存完成后，请按“导出本次多张 DNG”。切换界面不会自动结束采集。");}}
}
