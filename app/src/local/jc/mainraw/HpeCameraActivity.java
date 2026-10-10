package local.jc.mainraw;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;

/** Separate HPE research route. Never falls back to the main camera. */
public final class HpeCameraActivity extends Activity {
    private static final String CAMERA="4",MODE="org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    private HandlerThread thread;private Handler worker;private final ExecutorService lifecycle=Executors.newSingleThreadExecutor();
    private PairPreviewLease lease;private CameraManager manager;private CameraCharacteristics sensor;
    private CameraDevice device;private CameraCaptureSession session;private ImageReader raw;private Surface surface;
    private TextureView texture;private PairFocusOverlay focusOverlay;private volatile int focusUiToken;private long focusUiStart=Long.MAX_VALUE;private int lastOverlayState=-1;private TextView status;private Button shutter;private EditText exposureInput;
    private volatile boolean resumed,opening,busy;private volatile int generation;private int mode,format,warm,focusFrames;
    private float optical=1,previousFocus=Float.NaN;private long exposure;private File folder;private JSONObject report;
    private TotalCaptureResult latest;private long lastFrame;private Image pendingImage;private TotalCaptureResult pendingResult;private long expectedTimestamp;
    private boolean autoIssued,rawRoute,rawCaptureIssued,shootAfterRebuild;private float lockedDistance;private Runnable timeout;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setNavigationBarColor(Color.BLACK);
        mode=getIntent().getIntExtra("mode",4);if(mode!=4&&mode!=6)throw new IllegalArgumentException("HPE mode must be 4 or 6");
        format=getIntent().getIntExtra("format",ImageFormat.RAW_SENSOR);
        if(format!=ImageFormat.RAW_SENSOR)throw new IllegalArgumentException("HPE requires standard RAW_SENSOR; forced packed RAW14 rejected");
        exposure=getIntent().getLongExtra("exposureNs",10_000_000);optical=getIntent().getFloatExtra("opticalRatio",1f);
        thread=new HandlerThread("JC-HPE");thread.start();worker=new Handler(thread.getLooper());lease=new PairPreviewLease(this);
        manager=(CameraManager)getSystemService(CAMERA_SERVICE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.BLACK);setContentView(root);
        TextView title=text("HPE 长焦 · 独立实验入口",22);title.setGravity(Gravity.CENTER);root.addView(title);
        FrameLayout preview=new FrameLayout(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));texture=new TextureView(this);preview.addView(texture,new FrameLayout.LayoutParams(-1,-1));focusOverlay=new PairFocusOverlay(this);preview.addView(focusOverlay,new FrameLayout.LayoutParams(-1,-1));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture t,int a,int b){transform();start();}public void onSurfaceTextureSizeChanged(SurfaceTexture t,int a,int b){transform();}public boolean onSurfaceTextureDestroyed(SurfaceTexture t){return true;}public void onSurfaceTextureUpdated(SurfaceTexture t){}});
        status=text("正在准备长焦…",13);root.addView(status);
        LinearLayout modes=new LinearLayout(this);root.addView(modes);button(modes,"普通 RAW",()->change(4,32));button(modes,"DCG 14bit RAW",()->change(6,32));
        LinearLayout zoom=new LinearLayout(this);root.addView(zoom);button(zoom,"光学广端",()->zoom(3.2f));button(zoom,"光学长端",()->zoom(4.3f));
        exposureInput=new EditText(this);exposureInput.setTextColor(Color.WHITE);exposureInput.setSingleLine(true);exposureInput.setText(java.math.BigDecimal.valueOf(exposure,9).stripTrailingZeros().toPlainString());exposureInput.setHint("快门秒数，如 1/30");root.addView(exposureInput);
        LinearLayout bottom=new LinearLayout(this);root.addView(bottom);button(bottom,"返回主摄",()->finish());shutter=button(bottom,"拍一张 RAW",()->take());shutter.setEnabled(false);
        texture.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP&&!busy){
            if(rawRoute||session==null)return true;
            float x=e.getX(),y=e.getY(),w=texture.getWidth(),h=texture.getHeight();if(w<=0||h<=0)return true;
            Matrix inverse=new Matrix();if(!texture.getTransform(null).invert(inverse))return true;float[] point={x,y};inverse.mapPoints(point);
            if(point[0]<0||point[0]>=w||point[1]<0||point[1]>=h)return true;
            float u=point[0]/w,vv=point[1]/h;int token=++focusUiToken;focusOverlay.show(x,y);
            worker.post(()->tap(u,vv,token));
        }return true;});
    }
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(16,10,16,10);return t;}
    private Button button(LinearLayout row,String s,Runnable r){Button b=new Button(this);b.setText(s);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->r.run());return b;}
    private void say(String s){runOnUiThread(()->status.setText(s));}
    private void transform(){float a=texture.getWidth(),b=texture.getHeight();if(a==0||b==0)return;Matrix m=new Matrix();float k=(3072f/4080)*b/a;if(k>1)m.setScale(1,1/k,a/2,b/2);else m.setScale(k,1,a/2,b/2);texture.setTransform(m);}
    private synchronized void save(){if(folder==null||report==null)return;try{PairCaptureStore.atomic(new File(folder,"report.json"),report);}catch(Exception e){android.util.Log.e("JCHpe","save",e);}}
    private synchronized void event(String k,Object v){try{report.put(k,v);save();}catch(Exception e){android.util.Log.e("JCHpe",k,e);}}
    @Override protected void onResume(){super.onResume();resumed=true;start();}
    @Override protected void onPause(){clearFocusBox();resumed=false;generation++;lifecycle.execute(()->{try{closeCamera();lease.close();}catch(Exception e){android.util.Log.e("JCHpe","restore",e);}});super.onPause();}
    @Override protected void onDestroy(){lifecycle.execute(()->thread.quitSafely());lifecycle.shutdown();super.onDestroy();}
    private void start(){if(!resumed||!texture.isAvailable()||opening||device!=null)return;opening=true;int token=++generation;
        lifecycle.execute(()->{try{lease.open();if(!resumed||token!=generation){lease.close();return;}worker.post(()->open(token));}catch(Exception e){opening=false;say("长焦未就绪："+e.getMessage());}});
    }
    private void change(int m,int f){if(busy)return;mode=m;format=f;rebuild();}
    private void rebuild(){clearFocusBox();shutter.setEnabled(false);generation++;lifecycle.execute(()->{try{closeCamera();opening=false;runOnUiThread(this::start);}catch(Exception e){say("切换失败："+e.getMessage());}});}
    private void zoom(float ratio){if(busy)return;clearFocusBox();optical=ratio;if(report!=null&&report.has("terminal")){rebuild();return;}worker.post(()->{try{if(session!=null){event("requestedOpticalRatio",ratio);warm=0;session.setRepeatingRequest(request(false).build(),live,worker);}}catch(Exception e){say("变焦请求失败："+e.getMessage());}});}
    @SuppressWarnings("MissingPermission") private void open(int token){try{
        if(!resumed||token!=generation)return;sensor=manager.getCameraCharacteristics(CAMERA);
        if(sensor.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).getLower()!=20||sensor.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE).getWidth()!=4080)throw new IOException("Camera4 is not the verified HPE route");
        folder=new File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),"HPE/h"+System.currentTimeMillis());folder.mkdirs();report=new JSONObject();
        event("cameraId",CAMERA);event("requestedSensorMode",mode);event("format",format);event("requestedExposureNs",exposure);event("requestedIso",20);event("requestedOpticalRatio",optical);
        JSONObject info=new JSONObject();for(CameraCharacteristics.Key<?> k:sensor.getKeys())try{info.put(k.getName(),value(sensor.get(k)));}catch(Exception ignored){}
        PairCaptureStore.atomic(new File(folder,"characteristics.json"),info);
        JSONArray req=new JSONArray(),ses=new JSONArray();for(CaptureRequest.Key<?> k:sensor.getAvailableCaptureRequestKeys())req.put(k.getName());if(sensor.getAvailableSessionKeys()!=null)for(CaptureRequest.Key<?> k:sensor.getAvailableSessionKeys())ses.put(k.getName());event("requestKeys",req);event("sessionKeys",ses);
        manager.openCamera(CAMERA,new CameraDevice.StateCallback(){public void onOpened(CameraDevice c){opening=false;if(!resumed||token!=generation){c.close();return;}device=c;rawRoute=false;rawCaptureIssued=false;configure(token);}public void onDisconnected(CameraDevice c){c.close();device=null;fail("disconnected");}public void onError(CameraDevice c,int e){c.close();device=null;fail("camera_error_"+e);}},worker);
    }catch(Exception e){opening=false;fail(e.toString());}}
    private void configure(int token){try{
        SurfaceTexture st=texture.getSurfaceTexture();st.setDefaultBufferSize(1280,960);if(surface!=null)surface.release();surface=new Surface(st);
        raw=ImageReader.newInstance(4080,3072,ImageFormat.RAW_SENSOR,3,1048579L);
        raw.setOnImageAvailableListener(this::image,worker);
        SessionConfiguration config=new SessionConfiguration(rawRoute?getIntent().getIntExtra("dcgSessionType",0):0,Arrays.asList(new OutputConfiguration(surface),new OutputConfiguration(raw.getSurface())),r->worker.post(r),new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession s){if(!resumed||token!=generation){s.close();return;}session=s;warm=0;latest=null;if(!rawRoute)busy=false;focusFrames=0;try{session.setRepeatingRequest(request(false).build(),live,worker);}catch(Exception e){fail(e.toString());}}
            public void onConfigureFailed(CameraCaptureSession s){s.close();fail("session_rejected");}
        });config.setSessionParameters(request(false).build());device.createCaptureSession(config);
        worker.postDelayed(()->{if(resumed&&token==generation&&latest==null)fail("bounded_preview_timeout");},12000);
    }catch(Exception e){fail(e.toString());}}
    private <T> void vendor(CaptureRequest.Builder b,String name,Class<T> type,T v){b.set(new CaptureRequest.Key<T>(name,type),v);}
    private CaptureRequest.Builder request(boolean still)throws Exception{
        CaptureRequest.Builder b=device.createCaptureRequest(still?CameraDevice.TEMPLATE_STILL_CAPTURE:CameraDevice.TEMPLATE_PREVIEW);
        b.set(CaptureRequest.CONTROL_AE_MODE,rawRoute?0:1);b.set(CaptureRequest.SENSOR_SENSITIVITY,20);b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposure);b.set(CaptureRequest.SENSOR_FRAME_DURATION,Math.max(33_333_333L,exposure));b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,100);
        b.set(CaptureRequest.CONTROL_AF_MODE,rawRoute?0:busy?CaptureRequest.CONTROL_AF_MODE_AUTO:CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);if(rawRoute)b.set(CaptureRequest.LENS_FOCUS_DISTANCE,lockedDistance);b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,1);b.set(CaptureRequest.CONTROL_ZOOM_RATIO,1f);b.set(CaptureRequest.FLASH_MODE,0);
        int effectiveMode=rawRoute?mode:4;vendor(b,MODE,int[].class,new int[]{effectiveMode});vendor(b,HdrMode.DCG,int[].class,new int[]{effectiveMode==6?3588:0});vendor(b,HdrMode.EXPOSURES,int[].class,new int[]{1});vendor(b,HdrMode.LOFIC,int[].class,new int[]{0});
        if(optical!=1f||getIntent().getBooleanExtra("opticalTag",false)){vendor(b,"xiaomi.zoomRatio.debug.zoomRatioDebugEnable",int[].class,new int[]{1});vendor(b,"xiaomi.zoomRatio.debug.setZoomRatioCode",float[].class,new float[]{optical});vendor(b,"com.xiaomi.camera.userZoomRatio.userZoomRatio",float[].class,new float[]{optical});vendor(b,"xiaomi.snapshot.userZoomRatio",float[].class,new float[]{optical});}
        if(!rawRoute)b.addTarget(surface);if(still||rawRoute)b.addTarget(raw.getSurface());return b;
    }
    private void clearFocusBox(){focusUiToken++;focusOverlay.hide();worker.post(()->{focusUiStart=Long.MAX_VALUE;lastOverlayState=-1;});}
    private final CameraCaptureSession.CaptureCallback live=new CameraCaptureSession.CaptureCallback(){
        public void onCaptureStarted(CameraCaptureSession s,CaptureRequest q,long ts,long frame){if(s==session&&q.getTag() instanceof Integer&&((Integer)q.getTag())==focusUiToken)focusUiStart=frame;}
        public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest q,TotalCaptureResult r){if(s!=session)return;latest=r;lastFrame=r.getFrameNumber();warm++;JSONObject j=metadata(r);if(warm==1||warm==8||busy)event("latest",j);
        Integer af=r.get(CaptureResult.CONTROL_AF_STATE);Integer lens=r.get(CaptureResult.LENS_STATE);
        if(!rawRoute&&af!=null&&lastFrame>=focusUiStart){int token=focusUiToken,state=af;runOnUiThread(()->{if(token==focusUiToken)focusOverlay.state(state);});if(state!=lastOverlayState){lastOverlayState=state;android.util.Log.i("JCHpeFocus","tap="+token+" frame="+lastFrame+" af="+state);try{event("focusUi",new JSONObject().put("tapToken",token).put("frame",lastFrame).put("afState",state));}catch(JSONException ignored){}}}
        if(!rawRoute&&busy&&lastFrame>=focusStart){Float d=r.get(CaptureResult.LENS_FOCUS_DISTANCE);if(af!=null&&af==4&&lens!=null&&lens==0&&d!=null&&Float.isFinite(d)&&d>=0){if(Float.isFinite(previousFocus)&&Math.abs(d-previousFocus)<=.01f)focusFrames++;else focusFrames=1;previousFocus=d;}else{focusFrames=0;previousFocus=Float.NaN;}if(focusFrames>=3&&!autoIssued){autoIssued=true;capture();}}
        if(rawRoute&&warm==8&&busy&&!rawCaptureIssued){captureRaw();return;}
        if(!rawRoute&&warm==8){say("HPE · "+(mode==6?"DCG 待核验":"普通 RAW")+" · ISO "+r.get(CaptureResult.SENSOR_SENSITIVITY)+" · 点击主体对焦");runOnUiThread(()->shutter.setEnabled(true));if((shootAfterRebuild||getIntent().getBooleanExtra("autoProbe",false))&&!busy){shootAfterRebuild=false;runOnUiThread(()->take());}}
    }};
    private long focusStart=Long.MAX_VALUE;private MeteringRectangle[] focusRegion;
    private void tap(float u,float v,int token){try{if(session==null||rawRoute||token!=focusUiToken)return;focusUiStart=Long.MAX_VALUE;lastOverlayState=-1;Rect a=sensor.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);int size=400;int x=Math.max(0,Math.min(a.width()-size,(int)(v*a.width())-size/2)),y=Math.max(0,Math.min(a.height()-size,(int)((1-u)*a.height())-size/2));focusRegion=new MeteringRectangle[]{new MeteringRectangle(x,y,size,size,1000)};CaptureRequest.Builder b=request(false);b.set(CaptureRequest.CONTROL_AF_MODE,1);b.set(CaptureRequest.CONTROL_AF_REGIONS,focusRegion);session.setRepeatingRequest(b.build(),live,worker);b.set(CaptureRequest.CONTROL_AF_TRIGGER,1);b.setTag(token);session.capture(b.build(),live,worker);say("已选择长焦对焦位置");}catch(Exception e){say("对焦失败："+e.getMessage());}}
    private void take(){if(report!=null&&report.has("terminal")){shootAfterRebuild=true;rebuild();return;}if(busy||session==null||warm<8)return;try{exposure=PairManualPolicy.shutter(exposureInput.getText().toString());}catch(Exception e){say("请输入有效快门");return;}busy=true;autoIssued=false;focusFrames=0;previousFocus=Float.NaN;focusStart=Long.MAX_VALUE;shutter.setEnabled(false);say("确认长焦焦点…");worker.post(()->{try{
        event("requestedExposureNs",exposure);CaptureRequest.Builder b=request(false);if(focusRegion!=null)b.set(CaptureRequest.CONTROL_AF_REGIONS,focusRegion);session.setRepeatingRequest(b.build(),live,worker);b.set(CaptureRequest.CONTROL_AF_TRIGGER,1);session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){public void onCaptureStarted(CameraCaptureSession s,CaptureRequest r,long ts,long frame){focusStart=frame;}},worker);
        int token=generation;timeout=()->{if(token==generation&&busy&&!autoIssued){busy=false;event("terminal","focus_unconfirmed_before_shutter");say("未合焦，未拍摄；点击快门重新准备");runOnUiThread(()->shutter.setEnabled(true));}};worker.postDelayed(timeout,5000);
    }catch(Exception e){busy=false;fail(e.toString());}});}
    private void capture(){try{if(timeout!=null)worker.removeCallbacks(timeout);JSONObject gate=metadata(latest);event("lockedFocus",gate);
        if(gate.optInt("actualSensorMode",-1)!=4)throw new IOException("Normal AF route mismatch before shutter");
        Float focus=latest.get(CaptureResult.LENS_FOCUS_DISTANCE);if(focus==null||!Float.isFinite(focus)||focus<0)throw new IOException("Missing locked focus");lockedDistance=focus;
        if(mode==4||mode==6){say("RAW 准备中 · 画面暂缓更新");session.stopRepeating();session.close();session=null;raw.close();raw=null;device.close();device=null;rawRoute=true;int token=generation;manager.openCamera(CAMERA,new CameraDevice.StateCallback(){public void onOpened(CameraDevice c){if(!resumed||token!=generation){c.close();return;}device=c;configure(token);}public void onDisconnected(CameraDevice c){c.close();device=null;fail("raw_reopen_disconnected");}public void onError(CameraDevice c,int e){c.close();device=null;fail("raw_reopen_error_"+e);}},worker);return;}
        captureRaw();
    }catch(Exception e){busy=false;fail(e.toString());}}
    private void captureRaw(){try{
        JSONObject gate=metadata(latest);event("ready",gate);if(gate.optInt("actualSensorMode",-1)!=mode||gate.optInt("iso",-1)!=20)throw new IOException("Actual RAW route mismatch before shutter");if(optical!=1f){float[] ratio=latest.get(new CaptureResult.Key<float[]>("com.xiaomi.optical.zoom.opticalZoomCurrentRatio",float[].class));if(ratio==null||ratio.length!=1||Math.abs(ratio[0]-optical)>.02f)throw new IOException("Optical zoom not confirmed before shutter");}
        CaptureRequest.Builder b=request(true);b.set(CaptureRequest.CONTROL_AF_MODE,0);b.set(CaptureRequest.LENS_FOCUS_DISTANCE,lockedDistance);session.stopRepeating();
        rawCaptureIssued=true;pendingResult=null;expectedTimestamp=0;int token=generation;
        event("shutterIssued",true);session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){public void onCaptureStarted(CameraCaptureSession s,CaptureRequest q,long ts,long frame){if(s!=session)return;expectedTimestamp=ts;event("startedTimestampNs",ts);}public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest q,TotalCaptureResult r){if(s!=session)return;pendingResult=r;event("capture",metadata(r));pair();}public void onCaptureFailed(CameraCaptureSession s,CaptureRequest q,CaptureFailure f){if(s==session)fail("capture_failed_no_retry");}},worker);
        worker.postDelayed(()->{if(token==generation&&busy)fail("capture_timeout_no_retry");},8000);
    }catch(Exception e){busy=false;fail(e.toString());}}
    private JSONObject metadata(CaptureResult r){JSONObject j=new JSONObject();try{for(CaptureResult.Key<?> k:r.getKeys())try{j.put(k.getName(),value(r.get(k)));}catch(Exception ignored){}
        byte[] m=r.get(new CaptureResult.Key<byte[]>("org.quic.camera2.properties_sensor.SensorCurrentMode",byte[].class));int actual=m!=null&&m.length==1?m[0]&255:-1;if(actual<0){int[] a=r.get(new CaptureResult.Key<int[]>(MODE,int[].class));if(a!=null&&a.length==1)actual=a[0];}j.put("actualSensorMode",actual);
        j.put("timestampNs",r.get(CaptureResult.SENSOR_TIMESTAMP));j.put("iso",r.get(CaptureResult.SENSOR_SENSITIVITY));j.put("exposureNs",r.get(CaptureResult.SENSOR_EXPOSURE_TIME));j.put("focusDistance",r.get(CaptureResult.LENS_FOCUS_DISTANCE));
    }catch(Exception ignored){}return j;}
    private void image(ImageReader reader){Image im=null;try{im=reader.acquireNextImage();if(im==null)return;if(reader!=raw||!rawCaptureIssued||expectedTimestamp==0||im.getTimestamp()<expectedTimestamp)return;if(pendingImage!=null)throw new IOException("Unexpected additional image");pendingImage=im;im=null;pair();}catch(Exception e){fail(e.toString());}finally{if(im!=null)im.close();}}
    private void pair(){if(pendingImage==null||pendingResult==null)return;Image im=pendingImage;pendingImage=null;try{
        long ts=im.getTimestamp();JSONObject meta=metadata(pendingResult);if(ts!=meta.getLong("timestampNs")||ts!=expectedTimestamp)throw new IOException("RAW timestamp mismatch");if(Math.abs(meta.getLong("exposureNs")-exposure)>20000)throw new IOException("HPE exposure report differs from request");
        byte[] pixels;JSONObject buffer=new JSONObject().put("width",im.getWidth()).put("height",im.getHeight()).put("format",im.getFormat()).put("timestampNs",ts);
        Image.Plane p=im.getPlanes()[0];buffer.put("rowStride",p.getRowStride()).put("pixelStride",p.getPixelStride());ByteBuffer b=p.getBuffer();pixels=new byte[b.remaining()];b.get(pixels);

        String name="hpe_raw_"+format+"_"+ts+".raw";try(FileOutputStream out=new FileOutputStream(new File(folder,name))){out.write(pixels);}
        StringBuilder hash=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(pixels))hash.append(String.format(Locale.US,"%02x",v&255));buffer.put("file",name).put("bytes",pixels.length).put("sha256",hash.toString());event("buffer",buffer);
        int[] validation=HpeRawPolicy.check(pixels,im.getWidth(),im.getHeight(),p.getRowStride(),p.getPixelStride(),mode,meta.optInt("actualSensorMode",-1),meta.optInt("iso",-1),lockedDistance,(float)meta.optDouble("focusDistance",Double.NaN));event("rawValidation",new JSONObject().put("minimum",validation[0]).put("maximum",validation[1]).put("oddCodes",validation[2]).put("codeContainerBits",validation[3]).put("bitsPerSample",16).put("allRowsPresent",true).put("note","Container checked; effective precision and iDCG+DSG combination not certified"));
        File dng=new File(folder,mode==6?"hpe_dcg.dng":"hpe_normal.dng");try(DngCreator d=new DngCreator(sensor,pendingResult);FileOutputStream out=new FileOutputStream(dng)){d.setOrientation(6);d.writeImage(out,im);}if(mode==6){HpeDngMetadata.setContainerWhite(dng,1023,16383);event("dngWhiteLevel",new JSONObject().put("reportedValue",1023).put("exportValue",16383).put("authority","mode6_14bit_code_container_limit_not_measured_full_well"));}event("dng",dng.getName());
        event("terminal","raw_and_dng_saved");busy=false;say("长焦 RAW / DNG 已保存 · "+(mode==6?"DCG 14bit 容器":"普通 RAW"));runOnUiThread(()->{shutter.setText("再拍一张 RAW");shutter.setEnabled(true);});
    }catch(Exception e){busy=false;fail(e.toString());}finally{im.close();pendingResult=null;}}
    private void fail(String error){busy=false;if(report!=null)event("error",error);say("长焦未完成："+error);runOnUiThread(()->shutter.setEnabled(false));}
    private void closeCamera()throws Exception{CountDownLatch done=new CountDownLatch(1);worker.post(()->{try{if(session!=null){session.close();session=null;}if(device!=null){device.close();device=null;}if(pendingImage!=null){pendingImage.close();pendingImage=null;}if(raw!=null){raw.close();raw=null;}if(surface!=null){surface.release();surface=null;}opening=false;busy=false;rawCaptureIssued=false;pendingResult=null;expectedTimestamp=0;if(timeout!=null)worker.removeCallbacks(timeout);}finally{done.countDown();}});if(!done.await(4,TimeUnit.SECONDS))throw new IOException("HPE close timed out");}
    private static Object value(Object v)throws Exception{if(v==null)return JSONObject.NULL;if(v.getClass().isArray()){JSONArray a=new JSONArray();for(int i=0;i<java.lang.reflect.Array.getLength(v);i++)a.put(value(java.lang.reflect.Array.get(v,i)));return a;}if(v instanceof android.util.Rational)return v.toString();if(v instanceof Float&&!Float.isFinite((Float)v)||v instanceof Double&&!Double.isFinite((Double)v))return v.toString();if(v instanceof Number||v instanceof Boolean||v instanceof String)return v;return v.toString();}
}
