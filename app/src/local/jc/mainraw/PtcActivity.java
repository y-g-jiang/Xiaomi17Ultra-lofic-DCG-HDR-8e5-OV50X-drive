package local.jc.mainraw;
import android.app.*;
import android.os.*;
import android.content.*;
import android.widget.*;
import org.json.*;

/** Acquisition only: paired exposures, two lossless DNG branches per shot. */
public final class PtcActivity extends Activity {
    private TextView status;private Button start,stop,endpoints,minimum,resume,highlight;private EditText highStart,highEnd;private volatile boolean cancel,running;
    private NativePhotoBridge bridge;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(28,28,28,28);ScrollView scroll=new ScrollView(this);scroll.addView(l);setContentView(scroll);
        TextView intro=new TextView(this);intro.setTextSize(18);intro.setText("PTC 自动采集 · ISO 50\n约30.681µs至1秒，名义1/3档，按传感器曝光行取整并去重。43档配对，约50ms和500ms各补6张参考；共98次拍摄、196张原始DNG。\n\n只保存RAW14和配对RAW10，不合成、不扣黑、不降噪。每档两次实际曝光必须一致，否则停止。\n\n固定无限远；请用稳定均匀光源、固定手机，避免LED闪烁。两帧差分用于后续PTC，不在手机处理。采集时会自动往返原生相机。预计需要数十分钟；原始临时文件也会保留，建议至少20GB空间。");l.addView(intro);
        TextView ht=new TextView(this);ht.setText("LOFIC高光补拍：起止快门(ms)，1/6档、每档A/B，ISO50/无限远，延时5秒。单独批次，不续拍旧扫描。");l.addView(ht);
        highStart=new EditText(this);highStart.setSingleLine();highStart.setInputType(8194);highStart.setText("1.0");l.addView(highStart);
        highEnd=new EditText(this);highEnd.setSingleLine();highEnd.setInputType(8194);highEnd.setText("3.2");l.addView(highEnd);
        highlight=new Button(this);highlight.setText("开始LOFIC高光配对补拍（延时5秒）");l.addView(highlight);
        start=new Button(this);start.setText("开始完整 PTC 序列（延时5秒）");l.addView(start);
        endpoints=new Button(this);endpoints.setText("仅检查曝光两端（2次拍摄，延时5秒）");l.addView(endpoints);
        minimum=new Button(this);minimum.setText("验证最小请求23.821µs（1次，延时5秒）");l.addView(minimum);
        resume=new Button(this);resume.setText("继续最近未完成的 PTC 批次");l.addView(resume);
        stop=new Button(this);stop.setText("停止（当前拍摄保存并恢复后停止）");stop.setEnabled(false);l.addView(stop);
        status=new TextView(this);status.setText("尚未开始。不会自动拍摄。");l.addView(status);
        try{bridge=new NativePhotoBridge(this);}catch(Exception e){status.setText(e.toString());start.setEnabled(false);}
        highlight.setOnClickListener(v->begin(false,false,false,true));
        start.setOnClickListener(v->begin(false,false,false,false));endpoints.setOnClickListener(v->begin(true,false,false,false));minimum.setOnClickListener(v->begin(true,true,false,false));resume.setOnClickListener(v->begin(false,false,true,false));stop.setOnClickListener(v->{cancel=true;say("已请求停止，等待当前拍摄安全结束。");});
    }
    private void say(String s){runOnUiThread(()->status.setText(s));}
    private void begin(final boolean rangeOnly,final boolean minimumOnly,final boolean resumeScan,final boolean highScan){if(running)return;final long[] highTimes;try{highTimes=highScan?PtcPlan.highlightExposures(Double.parseDouble(highStart.getText().toString()),Double.parseDouble(highEnd.getText().toString())):null;}catch(Exception e){say("高光快门范围无效："+e.getMessage());return;}if(!NativePhotoBridge.WORKING.compareAndSet(false,true))return;
        running=true;cancel=false;highlight.setEnabled(false);highStart.setEnabled(false);highEnd.setEnabled(false);start.setEnabled(false);endpoints.setEnabled(false);minimum.setEnabled(false);resume.setEnabled(false);stop.setEnabled(true);
        new Thread(()->{
            int done=0;String batch=(highScan?"PTC_HIGHLIGHT_ISO50_":minimumOnly?"MIN_REQUEST_ISO50_":rangeOnly?"PTC_RANGE_ISO50_":"PTC_ISO50_")+System.currentTimeMillis();
            startForegroundService(new Intent(this,CaptureService.class));
            try{
                if(bridge.session()!=null)throw new java.io.IOException("先在主界面导出或恢复旧会话");
                if(getFilesDir().getUsableSpace()<(rangeOnly?2_000_000_000L:20_000_000_000L))throw new java.io.IOException("完整扫描要求至少20GB可用空间，包含保留的原始临时文件");
                // No camera preparation or capture until the settling delay has elapsed.
                long deadline=SystemClock.elapsedRealtime()+5000;
                while(!cancel&&SystemClock.elapsedRealtime()<deadline){
                    long remaining=deadline-SystemClock.elapsedRealtime();
                    if(remaining<=0)break;
                    say("请保持手机不动，"+((remaining+999)/1000)+"秒后开始 PTC 采集…");
                    Thread.sleep(Math.min(100,remaining));
                }
                JSONObject checkpoint=resumeScan?bridge.latestPtcCheckpoint():null;
                JSONObject completed=checkpoint==null?new JSONObject():checkpoint.getJSONObject("completed");
                if(checkpoint!=null){batch=checkpoint.getString("batch");done=completed.length();}
                long[] times=highScan?highTimes:minimumOnly?new long[]{23821L}:rangeOnly?new long[]{PtcPlan.exposures()[0],1_000_000_000L}:PtcPlan.exposures();
                outer:for(int i=0;i<times.length;i++){
                    long firstActual=-1;
                    int shotCount=highScan?2:rangeOnly?1:((i==29||i==39)?8:2);
                    for(int shot=0;shot<shotCount;shot++){
                        if(cancel)break outer;
                        String shotName=shot==0?"A":shot==1?"B":"R"+shot;
                        String key="L"+String.format(java.util.Locale.US,"%03d",i)+"_"+shotName;
                        if(completed.has(key)){firstActual=completed.getLong(key);continue;}
                        String label="第"+(i+1)+"/"+times.length+"档 "+(shot==0?"A":"B")+"，请求 "+times[i]/1e6+" ms";
                        say(label+"；正在准备…");bridge.prepare(times[i]/1e6,true);
                        java.util.concurrent.CountDownLatch opened=new java.util.concurrent.CountDownLatch(1);
                        final Exception[] failure=new Exception[1];
                        runOnUiThread(()->{try{Intent intent=new Intent();intent.setComponent(new ComponentName("com.android.camera","com.android.camera.Camera"));startActivity(intent);}catch(Exception e){failure[0]=e;}finally{opened.countDown();}});
                        if(!opened.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("打开相机超时");
                        if(failure[0]!=null)throw failure[0];
                        bridge.captureAndWait();
                        JSONObject tag=new JSONObject().put("highlightScan",highScan).put("minimumRequestCheck",minimumOnly).put("rangeCheckOnly",rangeOnly).put("batch",batch).put("level",i).put("shot",shotName).put("referenceStack",!highScan&&!rangeOnly&&(i==29||i==39)).put("shotsAtLevel",shotCount).put("requestedExposureNs",times[i]).put("pairExpectedExposureNs",firstActual).put("levelCount",times.length).put("linePeriodNs",PtcPlan.LINE_NS);
                        bridge.exportPtc(s->say(label+"\n"+s),tag);
                        firstActual=bridge.lastExportExposureNs;done++;
                        // Do not asynchronously bring PTC to front between camera launches.

                    }
                }
                say((cancel?"已停止":"扫描完成")+"，已保存 "+done+" 次拍摄、"+(done*2)+" 张原始DNG。\n下载/JCCamera/"+batch+"\n每档A/B配对；未完成的一对请勿用于PTC。");
            }catch(Exception e){say("序列停止："+e.getMessage()+"\n已保存 "+done+" 次。保留已有DNG，未自动重试。");}
            finally{try{bridge.recover();}catch(Exception e){say("恢复失败，请回主界面恢复："+e.getMessage());}try{showSelf();}catch(Exception ignored){}stopService(new Intent(this,CaptureService.class));running=false;NativePhotoBridge.WORKING.set(false);runOnUiThread(()->{highlight.setEnabled(true);highStart.setEnabled(true);highEnd.setEnabled(true);start.setEnabled(true);endpoints.setEnabled(true);minimum.setEnabled(true);resume.setEnabled(true);stop.setEnabled(false);});}
        },"JC-PTC").start();
    }
    private void showSelf(){runOnUiThread(()->{Intent i=new Intent(this,PtcActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(i);});}
    @Override public void onBackPressed(){if(running){cancel=true;say("等待当前拍摄结束并恢复后，再返回。");}else super.onBackPressed();}
}
