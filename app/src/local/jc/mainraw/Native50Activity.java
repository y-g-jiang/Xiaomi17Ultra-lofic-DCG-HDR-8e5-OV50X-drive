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

/** Minimal 1x native50 capture front end. */
public final class Native50Activity extends Activity {
    private TextureView texture;private ImageView held;private PairFocusOverlay overlay;
    private TextView status,telemetry;private Button shutter,exposureButton;
    private boolean manualExposure;private long manualExposureNs=33_333_333L,suggestedExposureNs=33_333_333L;
    private FixedIsoPreview preview;private PairPreviewLease lease;
    private final java.util.concurrent.ExecutorService lifecycle=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean resumed,busy;private volatile int token;private long meteredAt;
    private File base;private volatile File activeFolder;
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);return t;}
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(128);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        if(Intent.ACTION_MAIN.equals(getIntent().getAction())&&getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)){startActivity(new Intent(this,ClassicHdrActivity.class));finish();return;}
        base=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"Native50");base.mkdirs();
        manualExposure=getPreferences(0).getBoolean("manualExposure",false);manualExposureNs=getPreferences(0).getLong("manualExposureNs",33_333_333L);
        if(manualExposureNs<100_000L||manualExposureNs>1_000_000_000L){manualExposure=false;manualExposureNs=33_333_333L;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.BLACK);setContentView(root);
        TextView title=text("JC · 1× 50MP 双 RAW",19);title.setGravity(Gravity.CENTER);root.addView(title,new LinearLayout.LayoutParams(-1,dp(54)));
        FrameLayout viewport=new FrameLayout(this);root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        texture=new TextureView(this);viewport.addView(texture,new FrameLayout.LayoutParams(-1,-1));
        held=new ImageView(this);held.setScaleType(ImageView.ScaleType.FIT_CENTER);held.setVisibility(View.GONE);viewport.addView(held,new FrameLayout.LayoutParams(-1,-1));
        overlay=new PairFocusOverlay(this);viewport.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        telemetry=text("正在打开主摄…",14);telemetry.setPadding(dp(14),dp(8),dp(14),dp(8));telemetry.setBackgroundColor(0x66000000);viewport.addView(telemetry,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        status=text("最低 ISO 自动曝光 · 点按对焦 · 延时 10 秒\n官方 Bayer + 原生 QBayer · 同参数锁焦",13);status.setGravity(Gravity.CENTER);root.addView(status,new LinearLayout.LayoutParams(-1,dp(70)));
        FrameLayout dock=new FrameLayout(this);root.addView(dock,new LinearLayout.LayoutParams(-1,dp(108)));
        shutter=new Button(this);shutter.setText("●");shutter.setTextSize(48);shutter.setTextColor(Color.WHITE);shutter.setBackgroundColor(Color.TRANSPARENT);shutter.setContentDescription("延时十秒拍摄两张50MP RAW");dock.addView(shutter,new FrameLayout.LayoutParams(dp(100),dp(100),Gravity.CENTER));shutter.setOnClickListener(v->capture());
        Button files=new Button(this);files.setText("记录");FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(82),dp(60),Gravity.LEFT|Gravity.CENTER_VERTICAL);fp.leftMargin=dp(14);dock.addView(files,fp);files.setOnClickListener(v->records());
        exposureButton=new Button(this);exposureButton.setTextSize(13);FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(112),dp(64),Gravity.RIGHT|Gravity.CENTER_VERTICAL);ep.rightMargin=dp(12);dock.addView(exposureButton,ep);exposureButton.setOnClickListener(v->{if(!busy)configureExposure();});exposureLabel();
        preview=new FixedIsoPreview(this,texture,(ns,iso,h,message)->{if(h!=null&&ns>0&&iso==50){meteredAt=SystemClock.elapsedRealtime();suggestedExposureNs=PairExposurePolicy.tripod(ns,iso).exposureNs;long selected=manualExposure?manualExposureNs:suggestedExposureNs;overlay.state(preview.focusState());telemetry.setText(String.format(Locale.US,"50MP ISO 70 · %s快门 %s · %.3f ms\n%s · 自动测光预览 ISO %d / %.3f ms",manualExposure?"手动":"自动",shutterText(selected),selected/1e6,preview.focusStatus(),iso,ns/1e6));}else if(message!=null)telemetry.setText(message);});
        preview.preserveTapSelection(true);preview.controls(true,false,1_000_000);lease=new PairPreviewLease(this);
        texture.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP&&!busy){float w=texture.getWidth(),h=texture.getHeight(),scale=Math.min(w/960f,h/1280f),vw=960*scale,vh=1280*scale;float u=(e.getX()-(w-vw)/2)/vw,vv=(e.getY()-(h-vh)/2)/vh;if(u>=0&&u<=1&&vv>=0&&vv<=1){overlay.show(e.getX(),e.getY());preview.focusAt(vv,1-u);status.setText("已选择主体 · 等待绿色合焦框");}}return true;});
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){transform();start();}public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){transform();}public boolean onSurfaceTextureDestroyed(SurfaceTexture s){preview.stop();return true;}public void onSurfaceTextureUpdated(SurfaceTexture s){if(!busy&&held.getVisibility()==View.VISIBLE)held.setVisibility(View.GONE);}});
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},21);
        returned(getIntent());
    }
    private String shutterText(long ns){return ns<1_000_000_000L?String.format(Locale.US,"1/%.1f 秒",1e9/ns):"1 秒";}
    private void exposureLabel(){exposureButton.setText(manualExposure?"曝光 · 手动\n"+shutterText(manualExposureNs):"曝光 · 自动");status.setText((manualExposure?"手动快门 "+shutterText(manualExposureNs):"最低 ISO 自动曝光")+" · 点按对焦 · 延时 10 秒\n两张 50MP RAW 使用相同曝光与焦点");}
    private void configureExposure(){
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(18),dp(10),dp(18),dp(8));
        TextView note=new TextView(this);note.setText("官方 Bayer 和原生 QBayer 共用一个快门值，ISO 70 / 最低增益。支持 1/10000～1 秒；取景保持自动测光，拍摄使用手动值。");body.addView(note);
        TextView label=new TextView(this);label.setText("快门（秒，例如 1/125 或 0.008）");body.addView(label);
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);input.setText(java.math.BigDecimal.valueOf(manualExposure?manualExposureNs:suggestedExposureNs,9).stripTrailingZeros().toPlainString());body.addView(input);
        HorizontalScrollView scroll=new HorizontalScrollView(this);LinearLayout presets=new LinearLayout(this);scroll.addView(presets);body.addView(scroll);
        for(String value:new String[]{"1/10000","1/4000","1/2000","1/1000","1/500","1/250","1/125","1/60","1/30","1/15","1/8","1/4","1/2","1"}){Button b=new Button(this);b.setText(value);presets.addView(b);b.setOnClickListener(v->{input.setText(value);input.setSelection(value.length());});}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("50MP 双连拍曝光").setView(body).setPositiveButton("使用手动快门",null).setNeutralButton("自动曝光",(d,w)->{manualExposure=false;getPreferences(0).edit().putBoolean("manualExposure",false).apply();exposureLabel();}).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{long ns=PairManualPolicy.shutter(input.getText().toString());manualExposureNs=ns;manualExposure=true;getPreferences(0).edit().putBoolean("manualExposure",true).putLong("manualExposureNs",ns).apply();exposureLabel();dialog.dismiss();}catch(Exception e){input.setError("请输入 1/10000～1 秒，例如 1/125 或 0.008");}}));dialog.show();
    }
    private void transform(){float w=texture.getWidth(),h=texture.getHeight();if(w==0||h==0)return;float scale=Math.min(w/960f,h/1280f);Matrix m=new Matrix();m.setScale(960*scale/w,1280*scale/h,w/2,h/2);texture.setTransform(m);}
    private void start(){if(resumed&&!busy&&texture!=null&&texture.isAvailable()&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)lifecycle.execute(()->{try{if(!resumed||busy)return;lease.open();runOnUiThread(()->{if(resumed&&!busy)preview.start();});}catch(Exception e){runOnUiThread(()->status.setText("预览未就绪："+e.getMessage()));}});}
    @Override protected void onResume(){super.onResume();resumed=true;if(busy&&activeFolder!=null)new File(activeFolder,"handoff").delete();start();}
    @Override protected void onPause(){resumed=false;token++;if(busy&&activeFolder!=null&&!new File(activeFolder,"handoff").isFile())try{PairCaptureStore.atomic(new File(activeFolder,"cancel.json"),new JSONObject().put("cancelled",true));}catch(Exception ignored){}if(preview!=null){preview.stop();lifecycle.execute(()->{try{preview.stopAndWait();lease.close();}catch(Exception e){android.util.Log.e("JC50","release",e);}});}super.onPause();}
    @Override protected void onDestroy(){token++;if(preview!=null)preview.destroy();lifecycle.shutdown();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);start();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_LAUNCHER)){startActivity(new Intent(this,ClassicHdrActivity.class));finish();return;}returned(intent);}
    private void returned(Intent intent){if(intent.hasExtra("native50Returned")){busy=false;shutter.setEnabled(true);exposureButton.setEnabled(true);meteredAt=0;String id=intent.getStringExtra("native50Returned");try{File f=new File(base,id);boolean ok=PairCaptureStore.read(new File(f,"restored.json")).getBoolean("restored")&&new File(f,"complete.json").isFile();status.setText(ok?"两张 50MP RAW / DNG 已保存 · 正在加入下载目录":"本次未通过检查 · 失败原件已保留");if(ok)new Thread(()->{try{Native50Publish.publish(this,f);runOnUiThread(()->status.setText("已保存至 Download/JCCamera/Native50 · 点记录查看"));}catch(Exception e){runOnUiThread(()->status.setText("原件已保存，下载副本失败："+e.getMessage()));}},"JC50-Publish").start();}catch(Exception e){status.setText("未完成拍摄 · 记录已保留");}start();}}
    private void capture(){if(busy)return;if(meteredAt==0||SystemClock.elapsedRealtime()-meteredAt>1500){status.setText("测光未就绪");return;}if(base.getUsableSpace()<1_500_000_000L){status.setText("至少需要 1.5 GB 空间");return;}
        final boolean selectedManual=manualExposure;final long selectedShutterNs=manualExposureNs;
        final int generation=++token;final long pressed=SystemClock.elapsedRealtime();final String id="n"+System.currentTimeMillis();final File folder=new File(base,id);folder.mkdirs();activeFolder=folder;busy=true;shutter.setEnabled(false);exposureButton.setEnabled(false);
        new Thread(()->{try{
            long prep=SystemClock.elapsedRealtime();runOnUiThread(()->status.setText("10 秒倒计时 · 正在锁定曝光与焦点…"));FixedIsoPreview.FocusSample focus=preview.lockFocus();if(generation!=token||!resumed)throw new IOException("拍摄取消");PairExposurePolicy.Plan plan=selectedManual?new PairExposurePolicy.Plan(selectedShutterNs,1):PairExposurePolicy.tripod(focus.exposureNs,focus.iso);
            java.util.concurrent.FutureTask<Void> hold=new java.util.concurrent.FutureTask<>(()->{PairCaptureView.lastFrame=PairCaptureView.snapshot(texture);held.setImageBitmap(PairCaptureView.lastFrame);held.setVisibility(View.VISIBLE);status.setText("参数已锁定 · 核验及保存中，画面暂缓更新");return null;});runOnUiThread(hold);hold.get(2,java.util.concurrent.TimeUnit.SECONDS);
            preview.stopAndWait();lease.close();
            PairCaptureStore.atomic(new File(folder,"plan.json"),new JSONObject().put("pressedElapsedMs",pressed).put("preparationElapsedMs",prep).put("delayMs",10000).put("exposureMode",selectedManual?"manual":"auto").put("manualExposureNs",selectedManual?selectedShutterNs:0).put("exposureNs",plan.exposureNs).put("gain",1).put("focusDistance",focus.distance).put("afState",focus.state).put("meteredIso",focus.iso).put("meteredExposureNs",focus.exposureNs));
            File assets=new File(getFilesDir(),"native50");assets.mkdirs();for(String n:new String[]{"native50_capture.sh","pair_camx.txt","pair_mode0_module.bin"})try(InputStream in=getAssets().open(n);OutputStream out=new FileOutputStream(new File(assets,n))){byte[] buf=new byte[65536];int k;while((k=in.read(buf))>0)out.write(buf,0,k);}
            String cmd="nohup timeout -s TERM -k 30 120 sh "+RootProcess.quote(new File(assets,"native50_capture.sh").getAbsolutePath())+" "+RootProcess.quote(assets.getAbsolutePath())+" "+id+" "+plan.exposureNs+" "+String.format(Locale.US,"%.8f",focus.distance)+" "+RootProcess.quote(getApplicationInfo().sourceDir)+" "+(pressed+10000)+" > "+RootProcess.quote(new File(folder,"controller.log").getAbsolutePath())+" 2>&1 < /dev/null &";
            java.lang.Process p=RootProcess.start(cmd,true);if(!p.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)||p.exitValue()!=0)throw new IOException("控制器未启动");
        }catch(Exception e){try{PairCaptureStore.atomic(new File(folder,"failed.json"),new JSONObject().put("stage","before_capture").put("shutterIssued",false).put("error",e.toString()));}catch(Exception ignored){}runOnUiThread(()->{busy=false;shutter.setEnabled(true);exposureButton.setEnabled(true);held.setVisibility(View.GONE);status.setText("未拍摄："+e.getMessage());start();});}},"JC-Native50-Start").start();
    }
    private void records(){File[] f=base.listFiles(File::isDirectory);if(f==null||f.length==0){status.setText("还没有记录");return;}Arrays.sort(f,(a,b)->b.getName().compareTo(a.getName()));String[] labels=new String[f.length];for(int i=0;i<f.length;i++)labels[i]=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault()).format(new Date(Long.parseLong(f[i].getName().substring(1))))+(new File(f[i],"complete.json").isFile()?" · 已保存":" · 未完成");new AlertDialog.Builder(this).setTitle("50MP 双 RAW 记录").setItems(labels,(d,index)->{File folder=f[index];String msg="Download/JCCamera/Native50/"+folder.getName();try{JSONObject a=PairCaptureStore.read(new File(folder,"complete.json"));msg="ISO 70 / 1×增益\n实际曝光 "+a.getJSONObject("official").getLong("actualExposureNs")/1e6+" ms\n焦点 "+a.getJSONObject("plan").getDouble("focusDistance")+"\n\n"+msg+"\n\nofficial/official_bayer_50mp.dng\nqbayer/native_qbayer_50mp.dng";}catch(Exception ignored){}new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("关闭",null).show();}).setNegativeButton("关闭",null).show();}
}
