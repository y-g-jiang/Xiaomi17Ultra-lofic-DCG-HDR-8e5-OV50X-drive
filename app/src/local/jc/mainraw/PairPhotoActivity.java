package local.jc.mainraw;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Camera front end: bounded metered 50MP followed by fixed-shutter HDR. */
public final class PairPhotoActivity extends Activity {
    private TextureView texture;
    private ImageView heldPreview;
    private PairFocusOverlay focusOverlay;
    private TextView status, telemetry;
    private Button shutter;
    private Switch samplingMode;
    private Button manualSettings;
    private boolean manual;
    private long manualDelay,manual50,manualHdr;
    private volatile boolean tripod;
    private volatile int captureToken;
    private ImageButton album;
    private FixedIsoPreview preview;
    private PairPreviewLease previewLease;
    private final java.util.concurrent.ExecutorService lifecycle=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private boolean resumed;
    private long meteredNs, meteredAt;
    private int meteredIso;
    private File pairs;
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        pairs=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"Pairs");pairs.mkdirs();
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.BLACK);setContentView(root);
        TextView title=text("JC   ·   50MP + HDR",18);title.setGravity(Gravity.CENTER);root.addView(title,new LinearLayout.LayoutParams(-1,dp(52)));
        FrameLayout viewport=new FrameLayout(this);root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        texture=new TextureView(this);viewport.addView(texture,new FrameLayout.LayoutParams(-1,-1));
        heldPreview=new ImageView(this);heldPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);heldPreview.setVisibility(View.GONE);viewport.addView(heldPreview,new FrameLayout.LayoutParams(-1,-1));
        if(PairCaptureView.lastFrame!=null&&getIntent().hasExtra("pairSessionId")){heldPreview.setImageBitmap(PairCaptureView.lastFrame);heldPreview.setVisibility(View.VISIBLE);}
        focusOverlay=new PairFocusOverlay(this);viewport.addView(focusOverlay,new FrameLayout.LayoutParams(-1,-1));
        telemetry=text("正在打开相机…",12);telemetry.setPadding(dp(16),dp(8),dp(16),dp(8));telemetry.setBackgroundColor(0x66000000);
        viewport.addView(telemetry,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        status=text("50MP 自动曝光 ≤ 1/30 秒  ·  HDR 固定 1/30 秒",12);status.setGravity(Gravity.CENTER);status.setPadding(dp(8),dp(10),dp(8),dp(6));root.addView(status);
        tripod=getPreferences(0).getBoolean("tripod",false);
        manual=getPreferences(0).getBoolean("manual",false);manualDelay=getPreferences(0).getLong("manualDelay",4000);
        manual50=getPreferences(0).getLong("manual50",33333333);manualHdr=getPreferences(0).getLong("manualHdr",33333333);
        samplingMode=new Switch(this);samplingMode.setTextColor(Color.WHITE);samplingMode.setPadding(dp(16),0,dp(16),0);
        samplingMode.setChecked(tripod);root.addView(samplingMode);modeLabel();
        samplingMode.setOnCheckedChangeListener((button,checked)->{tripod=checked;getPreferences(0).edit().putBoolean("tripod",checked).apply();modeLabel();});
        manualSettings=new Button(this);manualSettings.setText("手动采样设置");manualSettings.setTextColor(Color.WHITE);manualSettings.setBackgroundColor(Color.TRANSPARENT);root.addView(manualSettings);
        manualSettings.setOnClickListener(v->{if(!busy)configureManual();});
        Button tele=new Button(this);tele.setText("切换 HPE 长焦");root.addView(tele);tele.setOnClickListener(v->{if(!busy)startActivity(new Intent(this,HpeCameraActivity.class));});
        FrameLayout dock=new FrameLayout(this);root.addView(dock,new LinearLayout.LayoutParams(-1,dp(112)));
        shutter=new Button(this);shutter.setText("●");shutter.setTextSize(48);shutter.setTextColor(Color.WHITE);shutter.setBackgroundColor(Color.TRANSPARENT);shutter.setContentDescription("拍摄 50MP 和 HDR");
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(96),dp(96),Gravity.CENTER);dock.addView(shutter,sp);
        album=new ImageButton(this);album.setBackgroundColor(0xff222222);album.setImageResource(android.R.drawable.ic_menu_gallery);album.setScaleType(ImageView.ScaleType.CENTER_CROP);album.setContentDescription("相册");
        FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(dp(58),dp(58),Gravity.LEFT|Gravity.CENTER_VERTICAL);ap.leftMargin=dp(24);dock.addView(album,ap);
        Button settings=new Button(this);settings.setText("设置");settings.setTextColor(Color.WHITE);settings.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(dp(76),dp(58),Gravity.RIGHT|Gravity.CENTER_VERTICAL);gp.rightMargin=dp(16);dock.addView(settings,gp);
        settings.setOnClickListener(v->{if(!busy)startActivity(new Intent(this,NativePhotoActivity.class));});
        preview=new FixedIsoPreview(this,texture,(ns,iso,h,message)->{
            if(h!=null && ns>0 && iso>0){meteredNs=ns;meteredIso=iso;meteredAt=SystemClock.elapsedRealtime();
                PairExposurePolicy.Plan plan=manual?new PairExposurePolicy.Plan(manual50,1):tripod?PairExposurePolicy.tripod(ns,iso):PairExposurePolicy.meter(ns,iso);
                focusOverlay.state(preview.focusState());
                if(!busy && status.getText().toString().startsWith("已选择对焦位置"))status.setText("已选择对焦位置 · "+preview.focusStatus());
                telemetry.setText(String.format(Locale.US,"1×   ·   ISO %d   ·   1/%.0f 秒   ·   %s",Math.round(70*plan.gain),1e9/plan.exposureNs,preview.focusStatus()));
            }else if(message!=null)telemetry.setText(message);
        },true);
        previewLease=new PairPreviewLease(this);
        preview.controls(true,false,1_000_000);
        texture.setOnTouchListener((v,event)->{if(event.getAction()!=MotionEvent.ACTION_UP||busy)return true;
            float w=texture.getWidth(),h=texture.getHeight(),scale=Math.min(w/960f,h/1280f),iw=960*scale,ih=1280*scale;
            float u=(event.getX()-(w-iw)/2)/iw,vv=(event.getY()-(h-ih)/2)/ih;
            if(u>=0&&u<=1&&vv>=0&&vv<=1){focusOverlay.show(event.getX(),event.getY());preview.focusAt(vv,1-u);status.setText("已选择对焦位置 · 正在对焦…");}return true;});
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){transform();startPreview();}
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){transform();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s){preview.stop();return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture s){if(!busy&&meteredAt>0)heldPreview.setVisibility(View.GONE);}
        });
        shutter.setOnClickListener(v->capture());album.setOnClickListener(v->{if(!busy)gallery();});
        refreshSaved();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},21);
    }
    private void modeLabel(){samplingMode.setEnabled(!manual&&!busy);samplingMode.setText(manual?"手动采样模式":tripod?"架采样模式":"手持采样模式");
        status.setText(manual?String.format(Locale.US,"延时 %.1f 秒 · 50MP %.3f ms · LOFIC %.3f ms",manualDelay/1000.0,manual50/1e6,manualHdr/1e6):tripod?"延迟 4 秒 · 50MP 最低 ISO 自动曝光 · HDR 快帧测光 → 慢帧":"延迟 4 秒 · 50MP ≤ 1/30 秒 · HDR 固定 1/30 秒");}
    private void configureManual(){
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView note=new TextView(this);note.setText("分别设置两帧快门，按50MP→LOFIC顺序拍摄。最低ISO：50MP 70，LOFIC 50。快门支持1/10000～1秒；延时结束后开始锁焦和核验。");body.addView(note);
        EditText delay=new EditText(this),mp=new EditText(this),hdr=new EditText(this);
        EditText[] fields={delay,mp,hdr};String[] labels={"拍摄延时（秒，0～60）","50MP快门（秒，如1/30或0.1）","LOFIC快门（秒，如1/60或0.2）"};
        String[] values={Double.toString(manualDelay/1000.0),java.math.BigDecimal.valueOf(manual50,9).stripTrailingZeros().toPlainString(),java.math.BigDecimal.valueOf(manualHdr,9).stripTrailingZeros().toPlainString()};
        for(int i=0;i<3;i++){TextView label=new TextView(this);label.setText(labels[i]);body.addView(label);fields[i].setSingleLine(true);fields[i].setText(values[i]);body.addView(fields[i]);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("手动采样设置").setView(body).setPositiveButton("启用手动",null)
                .setNeutralButton("返回自动模式",(d,w)->{manual=false;getPreferences(0).edit().putBoolean("manual",false).apply();modeLabel();}).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            long delayValue=PairManualPolicy.delay(delay.getText().toString()),mpValue=PairManualPolicy.shutter(mp.getText().toString()),hdrValue=PairManualPolicy.shutter(hdr.getText().toString());
            manualDelay=delayValue;manual50=mpValue;manualHdr=hdrValue;manual=true;
            getPreferences(0).edit().putBoolean("manual",true).putLong("manualDelay",manualDelay).putLong("manual50",manual50).putLong("manualHdr",manualHdr).apply();modeLabel();dialog.dismiss();
        }catch(IllegalArgumentException e){Toast.makeText(this,"输入无效："+e.getMessage(),Toast.LENGTH_LONG).show();}}));dialog.show();
    }
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Color.WHITE);return v;}
    private void transform(){float w=texture.getWidth(),h=texture.getHeight();if(w==0||h==0)return;
        // Camera2 already orients this back-camera SurfaceTexture for portrait.
        // Fit the entire 4:3 sensor image; an extra rotation or fill scale crops it.
        float scale=Math.min(w/960f,h/1280f);
        Matrix m=new Matrix();m.setScale(960f*scale/w,1280f*scale/h,w/2f,h/2f);
        texture.setTransform(m);
    }
    private void startPreview(){if(resumed&&!busy&&texture.isAvailable()&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
        lifecycle.execute(()->{try{if(!resumed||busy)return;previewLease.open();runOnUiThread(()->{if(resumed&&!busy)preview.start();});}
            catch(Exception e){runOnUiThread(()->status.setText("自动曝光未就绪："+e.getMessage()));}});}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);
        if(intent.hasExtra("pairSessionId")){busy=false;shutter.setEnabled(true);samplingMode.setEnabled(!manual);manualSettings.setEnabled(true);meteredAt=0;refreshSaved();startPreview();}}
    @Override protected void onResume(){super.onResume();resumed=true;startPreview();}
    @Override protected void onPause(){resumed=false;captureToken++;preview.stop();lifecycle.execute(()->{try{preview.stopAndWait();previewLease.close();}catch(Exception e){android.util.Log.e("JCPair","preview recovery",e);}});super.onPause();}
    @Override protected void onDestroy(){preview.destroy();lifecycle.shutdown();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==21)startPreview();}
    private void capture(){
        if(busy)return;
        if(pairs.getUsableSpace()<1_500_000_000L){status.setText("空间不足，至少需要 1.5 GB 可用空间");return;}
        if(meteredAt==0||SystemClock.elapsedRealtime()-meteredAt>1500){status.setText("测光尚未就绪，请稍候");return;}
        String id="p"+System.currentTimeMillis();File folder=new File(pairs,id);folder.mkdirs();
        final boolean onTripod=tripod,onManual=manual;final long delayMs=onManual?manualDelay:4000,manualExposure=manual50,hdrExposure=onManual?manualHdr:onTripod?NativeEttr.FIRST_NS:PairExposurePolicy.SHUTTER_NS;final int token=++captureToken;final long pressedAt=SystemClock.elapsedRealtime();
        busy=true;shutter.setEnabled(false);samplingMode.setEnabled(false);manualSettings.setEnabled(false);status.setText("等待开始采样…");
        new Thread(()->{try{
            long until=pressedAt+delayMs,lastSecond=-1;
            while(SystemClock.elapsedRealtime()<until){if(token!=captureToken||!resumed)throw new IOException("倒计时已取消");
                final long seconds=(until-SystemClock.elapsedRealtime()+999)/1000;if(seconds!=lastSecond){lastSecond=seconds;runOnUiThread(()->status.setText(seconds+" 秒后开始采样…"));}Thread.sleep(25);}
            if(token!=captureToken||!resumed)throw new IOException("倒计时已取消");
            final long preparationAt=SystemClock.elapsedRealtime();runOnUiThread(()->status.setText("正在确认焦点，请保持稳定…"));
            FixedIsoPreview.FocusSample locked=preview.lockFocus();
            if(token!=captureToken||!resumed)throw new IOException("Capture cancelled before shutter");
            PairFocusPlan focus=PairFocusPlan.read(locked);
            PairExposurePolicy.Plan plan=onManual?new PairExposurePolicy.Plan(manualExposure,1):onTripod?PairExposurePolicy.tripod(locked.exposureNs,locked.iso):PairExposurePolicy.meter(locked.exposureNs,locked.iso);
            java.util.concurrent.FutureTask<Void> hold=new java.util.concurrent.FutureTask<>(()->{
                PairCaptureView.lastFrame=PairCaptureView.snapshot(texture);heldPreview.setImageBitmap(PairCaptureView.lastFrame);heldPreview.setVisibility(View.VISIBLE);
                status.setText("核验中 · 保留取景画面，请保持稳定");return null;});runOnUiThread(hold);hold.get(2,java.util.concurrent.TimeUnit.SECONDS);
            preview.stopAndWait();
            previewLease.close();
            File assets=new File(getFilesDir(),"pair_capture");assets.mkdirs();
            for(String name:new String[]{"pair_capture.sh","pair_camx.txt","pair_mode0_module.bin","lofic_mode5_unity.so"}){
                try(InputStream in=getAssets().open(name);OutputStream out=new FileOutputStream(new File(assets,name))){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
            }
            PairCaptureStore.atomic(new File(folder,"plan.json"),new JSONObject().put("meteredExposureNs",locked.exposureNs).put("meteredIso",locked.iso)
                    .put("samplingMode",onManual?"manual":onTripod?"tripod":"handheld").put("pressedElapsedMs",pressedAt).put("preparationElapsedMs",preparationAt).put("delayMs",delayMs)
                    .put("mp50ExposureNs",plan.exposureNs).put("mp50Gain",plan.gain).put("hdrExposureNs",hdrExposure).put("focus",focus.json()));
            String cmd="nohup timeout -s TERM -k 120 100 sh "+RootProcess.quote(new File(assets,"pair_capture.sh").getAbsolutePath())+" "
                    +RootProcess.quote(assets.getAbsolutePath())+" "+id+" "+plan.exposureNs+" "+String.format(Locale.US,"%.6f",plan.gain)+" "+RootProcess.quote(getApplicationInfo().sourceDir)+" "+String.format(Locale.US,"%.8f",focus.focus.distance)+" "+(onManual?"manual":onTripod?"tripod":"handheld")+" "+hdrExposure
                    +" > "+RootProcess.quote(new File(folder,"controller.log").getAbsolutePath())+" 2>&1 < /dev/null &";
            java.lang.Process p=RootProcess.start(cmd,true);
            if(!p.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)||p.exitValue()!=0)throw new IOException("拍摄控制器未启动");
        }catch(Exception e){try{PairCaptureStore.atomic(new File(folder,"failed.json"),new JSONObject().put("stage","before_capture").put("error",e.toString()).put("shutterIssued",false));}catch(Exception ignored){}
            runOnUiThread(()->{busy=false;shutter.setEnabled(true);samplingMode.setEnabled(!manual);manualSettings.setEnabled(true);status.setText("未拍摄："+(e instanceof java.util.concurrent.TimeoutException?"未确认合焦，请点按主体后再拍":e.getMessage()));heldPreview.setVisibility(View.GONE);startPreview();});}},"JC-Pair-Start").start();
    }
    private ArrayList<File> albums(){ArrayList<File> out=new ArrayList<>();File[] all=pairs.listFiles();if(all!=null)for(File f:all)try{
        if(new File(f,"complete.json").isFile()&&PairCaptureStore.read(new File(f,"restored.json")).optBoolean("restored"))out.add(f);
    }catch(Exception ignored){}out.sort((a,b)->b.getName().compareTo(a.getName()));return out;}
    private void refreshSaved(){new Thread(()->{try{
        for(File folder:albums())PairCaptureStore.previews(folder,PairCaptureStore.read(new File(folder,"complete.json")));
        ArrayList<File> list=albums();runOnUiThread(()->{if(!list.isEmpty())album.setImageBitmap(BitmapFactory.decodeFile(new File(list.get(0),"50mp.jpg").getAbsolutePath()));});
        String id=getIntent().getStringExtra("pairSessionId");if(id!=null && id.matches("p[0-9]{13}")){
            File folder=new File(pairs,id);boolean ok=albums().contains(folder);
            runOnUiThread(()->status.setText(ok?"已保存 50MP 和 HDR · 点击左下角查看":"本次拍摄未通过检查，未加入相册"));
        }
    }catch(Exception e){android.util.Log.e("JCPair","preview export",e);runOnUiThread(()->status.setText("预览图生成失败，原始数据已保留"));}},"JC-Pair-Album").start();}
    private void gallery(){
        ArrayList<File> list=albums();if(list.isEmpty()){new AlertDialog.Builder(this).setMessage("还没有拍摄结果").setPositiveButton("关闭",null).show();return;}
        String[] labels=new String[list.size()];for(int i=0;i<labels.length;i++)labels[i]=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault()).format(new Date(Long.parseLong(list.get(i).getName().substring(1))));
        new AlertDialog.Builder(this).setTitle("相册").setItems(labels,(d,index)->showPair(list.get(index))).setNegativeButton("关闭",null).show();
    }
    private void showPair(File folder){try{
        JSONObject data=PairCaptureStore.read(new File(folder,"complete.json"));LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setBackgroundColor(Color.BLACK);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        if("tripod".equals(data.optString("samplingMode"))){
            JSONObject ettr=data.getJSONObject("ettr");
            String note=ettr.getDouble("top10Mean")>=1023?(NativeEttr.CHANNEL_RULE.equals(ettr.optString("rule"))?"各通道 Top10 均已饱和，第二帧未延长曝光":"快帧已饱和：第二帧未延长曝光"):ettr.optBoolean("limited")?"测光达到 1 秒曝光上限":(NativeEttr.CHANNEL_RULE.equals(ettr.optString("rule"))?"架采样模式 · 按 "+ettr.optString("selectedChannel")+" 通道测光":"架采样模式 · 快帧测光后曝光");
            TextView label=text(note,14);label.setPadding(dp(12),dp(12),dp(12),dp(8));body.addView(label);
        }
        if("manual".equals(data.optString("samplingMode"))){TextView label=text("手动采样模式",14);body.addView(label);}
        for(String name:new String[]{"50mp","hdr"}){
            JSONObject m=data.getJSONObject(name.equals("50mp")?"mp50":"hdr");
            TextView caption=text(name.equals("50mp")?String.format(Locale.US,"50MP · ISO %d · %.3f ms",m.getInt("iso"),m.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0)/1e6):String.format(Locale.US,"LOFIC + DCG HDR · %.3f ms",m.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0)/1e6),16);caption.setPadding(dp(12),dp(12),dp(12),dp(8));body.addView(caption);
            ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(BitmapFactory.decodeFile(new File(folder,name+".jpg").getAbsolutePath()));body.addView(image,new LinearLayout.LayoutParams(-1,-2));
        }
        new AlertDialog.Builder(this).setView(scroll).setPositiveButton("关闭",null).show();
    }catch(Exception e){status.setText("暂时无法打开这组照片");}}
}
