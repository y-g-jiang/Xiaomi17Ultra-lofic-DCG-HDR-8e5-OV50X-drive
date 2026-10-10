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

/** Default camera: single timestamp DCG RAW14 plus LOFIC RAW10. */
public final class ClassicHdrActivity extends Activity {
    private TextureView texture;private ImageView held;private PairFocusOverlay focusOverlay;
    private TextView telemetry,status,highlight;private Button shutter,exit;private ImageButton album;
    private ShutterDial dial;private Switch automatic;private SeekBar delayControl;
    private FixedIsoPreview preview;private ClassicDriverLease lease;
    private volatile boolean captureControllerActive;
    private final java.util.concurrent.ExecutorService lifecycle=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean busy,resumed,exiting;private volatile int token;
    private long delayMs,selectedNs,meterAt;private File pairs;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.WHITE);return v;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        pairs=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"Pairs");pairs.mkdirs();
        selectedNs=ShutterDialPolicy.exposure(ShutterDialPolicy.nearest(getPreferences(0).getLong("shutterNs",33333333)));
        // Migrate the old default once; subsequent deliberate timer choices persist.
        if(!getPreferences(0).getBoolean("zeroDelayDefault",false))getPreferences(0).edit().putLong("delayMs",0).putBoolean("zeroDelayDefault",true).apply();
        delayMs=Math.max(0,Math.min(60000,getPreferences(0).getLong("delayMs",0)));
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.BLACK);setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView title=text("JC · LOFIC + DCG HDR",16);title.setGravity(Gravity.CENTER);header.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        exit=new Button(this);exit.setText("恢复并退出");exit.setTextSize(12);exit.setTextColor(0xff72dec6);exit.setBackgroundColor(Color.TRANSPARENT);exit.setPadding(0,0,0,0);exit.setContentDescription("恢复原厂相机设置并退出软件");header.addView(exit,new LinearLayout.LayoutParams(dp(104),-1));exit.setOnClickListener(v->restoreAndExit());
        FrameLayout viewport=new FrameLayout(this);root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        texture=new TextureView(this);viewport.addView(texture,new FrameLayout.LayoutParams(-1,-1));
        held=new ImageView(this);held.setScaleType(ImageView.ScaleType.FIT_CENTER);held.setVisibility(View.GONE);viewport.addView(held,new FrameLayout.LayoutParams(-1,-1));
        if(getIntent().hasExtra("pairSessionId")&&PairCaptureView.lastFrame!=null){held.setImageBitmap(PairCaptureView.lastFrame);held.setVisibility(View.VISIBLE);}
        focusOverlay=new PairFocusOverlay(this);viewport.addView(focusOverlay,new FrameLayout.LayoutParams(-1,-1));
        telemetry=text("正在打开预览…",12);telemetry.setPadding(dp(12),dp(8),dp(12),dp(8));telemetry.setBackgroundColor(0x88000000);viewport.addView(telemetry,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        highlight=text("LOFIC 高光：待实拍核验 · 预览亮度不能判断 RAW 截顶",12);highlight.setPadding(dp(12),dp(8),dp(12),dp(4));root.addView(highlight);
        automatic=new Switch(this);automatic.setText("自动快门 · ISO 50");automatic.setTextColor(Color.WHITE);automatic.setPadding(dp(14),0,dp(14),0);automatic.setChecked(getPreferences(0).getBoolean("auto",false));root.addView(automatic);
        dial=new ShutterDial(this);dial.exposure(selectedNs);root.addView(dial,new LinearLayout.LayoutParams(-1,dp(92)));
        LinearLayout delayRow=new LinearLayout(this);delayRow.setGravity(Gravity.CENTER_VERTICAL);delayRow.setPadding(dp(12),0,dp(12),0);root.addView(delayRow);
        TextView delayLabel=text("延时 "+delayMs/1000+" 秒",12);delayRow.addView(delayLabel,new LinearLayout.LayoutParams(dp(82),-2));
        delayControl=new SeekBar(this);delayControl.setMax(60);delayControl.setProgress((int)(delayMs/1000));delayControl.setContentDescription("拍摄延时秒数");delayRow.addView(delayControl,new LinearLayout.LayoutParams(0,dp(36),1));
        delayControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean from){if(from&&!busy){delayMs=value*1000L;getPreferences(0).edit().putLong("delayMs",delayMs).apply();delayLabel.setText("延时 "+value+" 秒");}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        status=text("同一次曝光 · RAW10 + RAW14 · 点按对焦",12);status.setGravity(Gravity.CENTER);status.setPadding(dp(8),dp(4),dp(8),dp(4));root.addView(status);
        FrameLayout dock=new FrameLayout(this);root.addView(dock,new LinearLayout.LayoutParams(-1,dp(86)));
        shutter=new Button(this);shutter.setText("●");shutter.setTextSize(42);shutter.setTextColor(Color.WHITE);shutter.setBackgroundColor(Color.TRANSPARENT);shutter.setContentDescription("拍摄一组 RAW10 和 RAW14");dock.addView(shutter,new FrameLayout.LayoutParams(dp(96),-1,Gravity.CENTER));shutter.setOnClickListener(v->capture());
        album=new ImageButton(this);album.setBackgroundColor(0xff222222);album.setImageResource(android.R.drawable.ic_menu_gallery);album.setScaleType(ImageView.ScaleType.CENTER_CROP);album.setContentDescription("双 RAW 相册");FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.LEFT|Gravity.CENTER_VERTICAL);ap.leftMargin=dp(20);dock.addView(album,ap);album.setOnClickListener(v->{if(!busy)gallery();});
        Button modes=new Button(this);modes.setText("模式");modes.setTextColor(Color.WHITE);modes.setBackgroundColor(Color.TRANSPARENT);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(76),dp(56),Gravity.RIGHT|Gravity.CENTER_VERTICAL);mp.rightMargin=dp(12);dock.addView(modes,mp);modes.setOnClickListener(v->{if(!busy)new AlertDialog.Builder(this).setTitle("相机模式").setItems(new String[]{"50MP 原生 QBayer 双 RAW","50MP + HDR 采样","HPE 长焦","原生采集工具"},(d,i)->switchMode(new Class[]{Native50Activity.class,PairPhotoActivity.class,HpeCameraActivity.class,NativePhotoActivity.class}[i])).setNegativeButton("关闭",null).show();});
        preview=new FixedIsoPreview(this,texture,(ns,iso,h,message)->{
            if(h!=null&&ns>0&&iso==50){meterAt=SystemClock.elapsedRealtime();focusOverlay.state(preview.focusState());
                String chosen=automatic.isChecked()?"自动":ShutterDialPolicy.label(ShutterDialPolicy.nearest(selectedNs));
                telemetry.setText(String.format(Locale.US,"ISO 50 · 设置 %s · 实际预览 %.3f ms\n%s · 普通 ISP 取景，非 LOFIC RAW",chosen,ns/1e6,preview.focusStatus()));
                if(automatic.isChecked())dial.exposure(ns);
            }else if(message!=null)telemetry.setText(message);
        });preview.preserveTapSelection(true);lease=new ClassicDriverLease(this);
        automatic.setOnCheckedChangeListener((v,on)->{getPreferences(0).edit().putBoolean("auto",on).apply();controls();});
        dial.listener(ns->{if(busy)return;selectedNs=ns;getPreferences(0).edit().putLong("shutterNs",ns).putBoolean("auto",false).apply();automatic.setChecked(false);controls();});controls();
        texture.setOnTouchListener((v,e)->{if(e.getAction()!=MotionEvent.ACTION_UP||busy)return true;float w=texture.getWidth(),h=texture.getHeight(),s=Math.min(w/960f,h/1280f),iw=960*s,ih=1280*s;float u=(e.getX()-(w-iw)/2)/iw,y=(e.getY()-(h-ih)/2)/ih;if(u>=0&&u<=1&&y>=0&&y<=1){focusOverlay.show(e.getX(),e.getY());preview.focusAt(y,1-u);}return true;});
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){transform();start();}public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){transform();}public boolean onSurfaceTextureDestroyed(SurfaceTexture s){preview.stop();return true;}public void onSurfaceTextureUpdated(SurfaceTexture s){if(!busy&&meterAt>0)held.setVisibility(View.GONE);}});
        refresh();if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},26);
    }
    private void restoreAndExit(){
        if(busy)return;busy=true;exiting=true;token++;enabled(false);exit.setText("正在恢复…");
        try{PairCaptureView.lastFrame=PairCaptureView.snapshot(texture);held.setImageBitmap(PairCaptureView.lastFrame);held.setVisibility(View.VISIBLE);}catch(Exception ignored){}
        status.setText("正在恢复原厂相机设置…");
        lifecycle.execute(()->{try{preview.stopAndWait();lease.close();runOnUiThread(()->{Toast.makeText(this,"相机设置已恢复",Toast.LENGTH_SHORT).show();finishAndRemoveTask();});}
            catch(Exception e){android.util.Log.e("JCClassic","exit restore",e);runOnUiThread(()->{busy=false;exiting=false;enabled(true);exit.setText("恢复并退出");status.setText("恢复未完成，暂未退出："+e.getMessage());});}});
    }
    @Override public void onBackPressed(){if(busy){Toast.makeText(this,"请等待当前操作完成",Toast.LENGTH_SHORT).show();return;}restoreAndExit();}
    private void switchMode(Class<?> mode){
        if(busy)return;busy=true;enabled(false);preview.stop();status.setText("正在恢复相机配置…");
        lifecycle.execute(()->{try{lease.close();runOnUiThread(()->{busy=false;enabled(true);if(resumed)startActivity(new Intent(this,mode));});}catch(Exception e){runOnUiThread(()->{busy=false;enabled(true);status.setText("恢复未完成："+e.getMessage());});}});
    }
    private void controls(){if(preview!=null)preview.controls(automatic.isChecked(),false,selectedNs);}
    private void transform(){float w=texture.getWidth(),h=texture.getHeight();if(w<=0||h<=0)return;float s=Math.min(w/960f,h/1280f);Matrix m=new Matrix();m.setScale(960*s/w,1280*s/h,w/2,h/2);texture.setTransform(m);}
    private void start(){if(resumed&&!busy&&!exiting&&texture.isAvailable()&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)lifecycle.execute(()->{try{if(!resumed||busy)return;lease.open();runOnUiThread(()->{if(resumed&&!busy){controls();preview.start();}});}catch(Exception e){runOnUiThread(()->status.setText("预览未就绪："+e.getMessage()));}});}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==26&&g.length>0&&g[0]==0)start();}
    @Override protected void onResume(){super.onResume();resumed=true;start();}
    @Override protected void onPause(){resumed=false;token++;preview.stop();if(!captureControllerActive)lifecycle.execute(()->{try{lease.close();}catch(Exception e){android.util.Log.e("JCClassic","lease restore",e);}});super.onPause();}
    @Override protected void onDestroy(){preview.destroy();lifecycle.shutdown();super.onDestroy();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);if(i.hasExtra("pairSessionId")){captureControllerActive=false;busy=false;enabled(true);meterAt=0;refresh();start();}}
    private void enabled(boolean value){exit.setEnabled(value);shutter.setEnabled(value);automatic.setEnabled(value);dial.setEnabled(value);delayControl.setEnabled(value);}
    private void capture(){if(busy)return;if(meterAt==0||SystemClock.elapsedRealtime()-meterAt>3500){status.setText("等待新鲜预览，暂未发快门");return;}
        final int generation=++token;final long pressed=SystemClock.elapsedRealtime(),delay=delayMs,manualNs=selectedNs;final boolean auto=automatic.isChecked();
        String id="p"+System.currentTimeMillis();File folder=new File(pairs,id);if(!folder.mkdir()){status.setText("无法建立记录");return;}busy=true;enabled(false);
        new Thread(()->{try{
            long left;while((left=pressed+delay-SystemClock.elapsedRealtime())>0){if(generation!=token||!resumed)throw new IOException("拍摄已取消");final long seconds=(left+999)/1000;runOnUiThread(()->status.setText(seconds+" 秒后开始锁焦与核验"));Thread.sleep(Math.min(100,left));}
            long prep=SystemClock.elapsedRealtime();FixedIsoPreview.FocusSample locked=preview.lockFocus();if(generation!=token||!resumed)throw new IOException("拍摄已取消");
            long ns=auto?Math.max(100000,Math.min(1000000000L,locked.exposureNs)):manualNs;PairFocusPlan focus=PairFocusPlan.read(locked);
            java.util.concurrent.FutureTask<Void> hold=new java.util.concurrent.FutureTask<>(()->{PairCaptureView.lastFrame=PairCaptureView.snapshot(texture);held.setImageBitmap(PairCaptureView.lastFrame);held.setVisibility(View.VISIBLE);status.setText("参数已锁定 · 画面暂缓更新");return null;});runOnUiThread(hold);hold.get(2,java.util.concurrent.TimeUnit.SECONDS);
            preview.stopAndWait();if(generation!=token||!resumed)throw new IOException("拍摄已取消");
            String driverWork=lease.work();
            PairCaptureStore.atomic(new File(folder,"plan.json"),new JSONObject().put("samplingMode","classic_hdr").put("driverLeaseWork",driverWork).put("pressedElapsedMs",pressed).put("preparationElapsedMs",prep).put("delayMs",delay).put("hdrExposureNs",ns).put("exposureMode",auto?"auto":"dial").put("focus",focus.json()));
            File assets=new File(getFilesDir(),"classic_capture");
            String cmd="nohup timeout -s TERM -k 120 100 sh "+RootProcess.quote(new File(assets,"pair_capture.sh").getAbsolutePath())+" "+RootProcess.quote(assets.getAbsolutePath())+" "+id+" "+ns+" 1 "+RootProcess.quote(getApplicationInfo().sourceDir)+" "+String.format(Locale.US,"%.8f",locked.distance)+" classic "+ns+" "+RootProcess.quote(driverWork)+" > "+RootProcess.quote(new File(folder,"controller.log").getAbsolutePath())+" 2>&1 < /dev/null &";
            captureControllerActive=true;java.lang.Process p=RootProcess.start(cmd,true);if(!p.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)||p.exitValue()!=0)throw new IOException("控制器未启动");
        }catch(Exception e){captureControllerActive=false;if(!resumed)lifecycle.execute(()->{try{lease.close();}catch(Exception restore){android.util.Log.e("JCClassic","restore",restore);}});try{PairCaptureStore.atomic(new File(folder,"failed.json"),new JSONObject().put("stage","before_capture").put("shutterIssued",false).put("error",e.toString()));}catch(Exception ignored){}runOnUiThread(()->{busy=false;enabled(true);held.setVisibility(View.GONE);status.setText("未拍摄："+e.getMessage());start();});}},"JC-Classic-Start").start();
    }
    private ArrayList<File> albums(){ArrayList<File> out=new ArrayList<>();File[] all=pairs.listFiles(File::isDirectory);if(all!=null)for(File f:all)try{JSONObject r=PairCaptureStore.read(new File(f,"complete.json"));if("classic_hdr".equals(r.optString("samplingMode"))&&PairCaptureStore.read(new File(f,"restored.json")).optBoolean("restored"))out.add(f);}catch(Exception ignored){}out.sort((a,b)->b.getName().compareTo(a.getName()));return out;}
    private String highText(JSONObject r)throws Exception{JSONObject h=r.getJSONObject("highlights");JSONArray clip=h.getJSONArray("channelClippedPixels");long any=0;for(int i=0;i<4;i++)any+=clip.getLong(i);long all=h.getLong("allFourClipped2x2Cells");String state=all>0?"有四通道同时截顶位置":any>0?"部分通道截顶":"四通道均无码值截顶";return "实拍 LOFIC："+state+"\n"+h.getString("bestChannel")+" Top10 余量约 "+(h.isNull("bestChannelHeadroomEv")?"不可估算":String.format(Locale.US,"%.2f EV",h.getDouble("bestChannelHeadroomEv")));}
    private void refresh(){new Thread(()->{try{ArrayList<File> list=albums();for(File f:list){JSONObject r=PairCaptureStore.read(new File(f,"complete.json"));ClassicHdrStore.preview(f,r);ClassicHdrPublish.publish(this,f,r);}if(!list.isEmpty()){File f=list.get(0);JSONObject r=PairCaptureStore.read(new File(f,"complete.json"));String info=highText(r);runOnUiThread(()->{album.setImageBitmap(BitmapFactory.decodeFile(new File(f,"hdr.jpg").getAbsolutePath()));highlight.setText("上一张 · "+info);});}String id=getIntent().getStringExtra("pairSessionId");if(id!=null)runOnUiThread(()->status.setText(list.stream().anyMatch(f->f.getName().equals(id))?"双 RAW 已保存 · 左下角查看":"本次未通过检查，原件已保留"));}catch(Exception e){runOnUiThread(()->status.setText("原件保留，下载或预览生成失败："+e.getMessage()));}},"JC-Classic-Album").start();}
    private void gallery(){ArrayList<File> list=albums();if(list.isEmpty()){status.setText("还没有经典双 RAW 结果");return;}String[] names=new String[list.size()];for(int i=0;i<names.length;i++)names[i]=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.US).format(new Date(Long.parseLong(list.get(i).getName().substring(1))));new AlertDialog.Builder(this).setTitle("RAW10 + RAW14").setItems(names,(d,i)->show(list.get(i))).setNegativeButton("关闭",null).show();}
    private void show(File f){try{JSONObject r=PairCaptureStore.read(new File(f,"complete.json")),h=r.getJSONObject("highlights");LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setBackgroundColor(Color.BLACK);TextView note=text(highText(r)+"\n实际曝光 "+String.format(Locale.US,"%.3f ms",r.getJSONObject("hdr").getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0)/1e6),14);note.setPadding(dp(12),dp(12),dp(12),dp(12));body.addView(note);
        JSONArray clip=h.getJSONArray("channelClippedPixels"),top=h.getJSONArray("channelTop10Means");for(int i=0;i<4;i++)body.addView(text(String.format(Locale.US,"%s · 截顶 %.4f%% · Top10 %.1f",NativeEttr.CHANNELS[i],100.0*clip.getLong(i)/LoficHighlights.CHANNEL_PIXELS,top.getDouble(i)),13));
        ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setImageBitmap(BitmapFactory.decodeFile(new File(f,"hdr.jpg").getAbsolutePath()));body.addView(image,new LinearLayout.LayoutParams(-1,-2));body.addView(text("显示用同帧合成预览\nDownload/JCCamera/ClassicHDR/"+f.getName()+"\ndcg_raw14.dng · lofic_raw10.dng",12));ScrollView scroll=new ScrollView(this);scroll.addView(body);new AlertDialog.Builder(this).setView(scroll).setPositiveButton("关闭",null).show();}catch(Exception e){status.setText("读取照片失败："+e.getMessage());}}
}
