package local.jc.mainraw;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.os.*;
import android.util.*;
import android.view.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Display-only AE: manual ISO50, metering changes shutter only. YUV is not LOFIC. */
public final class FixedIsoPreview {
    public interface Listener {void update(long ns,int iso,int[] histogram,String state);}
    private boolean autoIso;
    private final Activity host;private final TextureView texture;private final Listener listener;
    private final HandlerThread thread=new HandlerThread("JC-LivePreview");private final Handler worker;
    private CameraDevice camera;private CameraCaptureSession session;private ImageReader meter;private Surface surface;
    private CameraCharacteristics sensor;private String logical,physical;private int generation;private boolean opening;private int openRetries;
    private volatile boolean enabled,auto=true,infinity=true;private volatile long exposure=3_750_000,actual;
    private MeteringRectangle[] focusRegions;
    private volatile int focusState=-1;
    private volatile float focusDistance=Float.NaN;
    private volatile long focusAt, openedAt;
    private boolean pointFocus;private int pointSerial;private boolean preserveTap;
    public void preserveTapSelection(boolean value){preserveTap=value;}
    private java.util.concurrent.CompletableFuture<FocusSample> focusWaiter;
    private long focusStartFrame=Long.MAX_VALUE,lastFocusFrame=-1;
    private int lockedFrames;private float previousLockedDistance=Float.NaN;
    public static final class FocusSample {
        public final float distance;public final long wallMs, previewSince, exposureNs;public final int state,iso;
        FocusSample(float d,long wall,long since,int s,long ns,int iso){distance=d;wallMs=wall;previewSince=since;state=s;exposureNs=ns;this.iso=iso;}
    }
    public int focusState(){return focusState;}
    public String focusStatus(){return focusState==2||focusState==4?"已合焦":focusState==5||focusState==6?"未合焦":"对焦中";}
    public void focusAt(float sensorX,float sensorY){worker.post(()->{try{
        if(sensor==null||session==null)return;
        Rect active=sensor.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer max=sensor.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        if(active==null||max==null||max<1){report("主摄不支持点按区域对焦");return;}
        android.util.Log.i("JCFocus","tap sensor="+sensorX+","+sensorY+" active="+active);
        int side=Math.max(32,Math.min(active.width(),active.height())/8);
        int x=Math.max(active.left,Math.min(active.right-side,active.left+Math.round(sensorX*active.width())-side/2));
        int y=Math.max(active.top,Math.min(active.bottom-side,active.top+Math.round(sensorY*active.height())-side/2));
        session.capture(previewRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL).build(),focusCallback,worker);
        focusRegions=new MeteringRectangle[]{new MeteringRectangle(x,y,side,side,1000)};pointFocus=true;focusState=3;repeat();
        int serial=++pointSerial;worker.postDelayed(()->{try{
            if(preserveTap||serial!=pointSerial||focusWaiter!=null||session==null||!enabled)return;
            session.capture(previewRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL).build(),focusCallback,worker);
            pointFocus=false;focusRegions=null;repeat();
        }catch(Exception e){report("连续对焦恢复失败");}},8000);
        session.capture(previewRequest(CaptureRequest.CONTROL_AF_TRIGGER_START).build(),focusCallback,worker);
    }catch(Exception e){report("对焦失败："+e.getMessage());}});}
    public FocusSample lockFocus() throws Exception {
        java.util.concurrent.CompletableFuture<FocusSample> wait=new java.util.concurrent.CompletableFuture<>();
        worker.post(()->{try{
            if(session==null||!enabled)throw new IllegalStateException("预览未就绪");
            focusWaiter=wait;focusStartFrame=Long.MAX_VALUE;lockedFrames=0;previousLockedDistance=Float.NaN;
            // Preserve a confirmed tap lock; still require three new stationary results.
            if(pointFocus && focusState==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED && SystemClock.elapsedRealtime()-focusAt<500)focusStartFrame=lastFocusFrame+1;
            else session.capture(previewRequest(CaptureRequest.CONTROL_AF_TRIGGER_START).build(),focusCallback,worker);
        }catch(Exception e){wait.completeExceptionally(e);}});
        try{return wait.get(Math.max(4000,1000+5*exposureNs()/1000000),java.util.concurrent.TimeUnit.MILLISECONDS);}
        finally{worker.post(()->{if(focusWaiter==wait){focusWaiter=null;try{
            if(session!=null&&enabled){session.capture(previewRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL).build(),focusCallback,worker);pointFocus=false;focusRegions=null;pointSerial++;repeat();}
        }catch(Exception e){report("连续对焦恢复失败");}}});}
    }
    private final CameraCaptureSession.CaptureCallback focusCallback=new CameraCaptureSession.CaptureCallback(){
        public void onCaptureStarted(CameraCaptureSession s,CaptureRequest r,long timestamp,long frame){
            if(s==session&&focusWaiter!=null&&Integer.valueOf(CaptureRequest.CONTROL_AF_TRIGGER_START).equals(r.get(CaptureRequest.CONTROL_AF_TRIGGER)))focusStartFrame=frame;
        }
        public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult all){
            if(s!=session)return;lastFocusFrame=all.getFrameNumber();CaptureResult a=physical==null?all:all.getPhysicalCameraResults().get(physical);if(a==null)a=all;
            Long t=a.get(CaptureResult.SENSOR_EXPOSURE_TIME);Integer iso=a.get(CaptureResult.SENSOR_SENSITIVITY);
            if(t!=null)actual=t;if(iso!=null)actualIso=iso;
            Integer af=a.get(CaptureResult.CONTROL_AF_STATE);if(af==null)af=all.get(CaptureResult.CONTROL_AF_STATE);
            Float distance=a.get(CaptureResult.LENS_FOCUS_DISTANCE);Integer moving=a.get(CaptureResult.LENS_STATE);
            if(af!=null){if(af!=focusState)android.util.Log.i("JCFocus","state="+af+" distance="+distance+" lens="+moving+" point="+pointFocus);focusState=af;}if(distance!=null)focusDistance=distance;focusAt=SystemClock.elapsedRealtime();
            if(focusWaiter!=null && all.getFrameNumber()>=focusStartFrame && af!=null && af==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    && moving!=null && moving==CaptureResult.LENS_STATE_STATIONARY && distance!=null && Float.isFinite(distance)){
                lockedFrames=Float.isFinite(previousLockedDistance)&&Math.abs(distance-previousLockedDistance)>.01f?1:lockedFrames+1;
                previousLockedDistance=distance;
                if(lockedFrames>=3){focusWaiter.complete(new FocusSample(distance,System.currentTimeMillis(),openedAt,af,actual,actualIso));focusWaiter=null;}
            }else if(focusWaiter!=null){lockedFrames=0;previousLockedDistance=Float.NaN;}
            if(!autoIso&&iso!=null&&iso!=50){report("实际ISO偏离50，预览已停止");close();}
        }
    };
    private long minNs=30_682,maxNs=1_000_000_000,lastMeter;private int actualIso;
    public FixedIsoPreview(Activity a,TextureView t,Listener l){host=a;texture=t;listener=l;thread.start();worker=new Handler(thread.getLooper());}
    public FixedIsoPreview(Activity a,TextureView t,Listener l,boolean autoIso){this(a,t,l);this.autoIso=autoIso;}
    public long exposureNs(){return actual>0?actual:exposure;}
    public void controls(boolean ae,boolean inf,long ns){auto=ae;infinity=inf;if(!ae)exposure=Math.max(minNs,Math.min(maxNs,ns));worker.post(()->{try{repeat();}catch(Exception e){report(e.toString());}});}
    private void report(String s){host.runOnUiThread(()->listener.update(exposureNs(),actualIso,null,s));}
    public void start(){enabled=true;worker.post(()->{openRetries=0;open();});}
    private void open(){
        if(!enabled||opening||camera!=null||!texture.isAvailable())return;
        if(host.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){report("允许相机权限后显示预览");return;}
        try{
            CameraManager m=(CameraManager)host.getSystemService(Activity.CAMERA_SERVICE);float best=-1;
            for(String id:m.getCameraIdList()){CameraCharacteristics c=m.getCameraCharacteristics(id);if(!Integer.valueOf(1).equals(c.get(CameraCharacteristics.LENS_FACING)))continue;SizeF s=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);float area=s==null?0:s.getWidth()*s.getHeight();if(area>best){best=area;logical=id;sensor=c;}}
            if(logical==null)throw new IllegalStateException("没有主摄");physical=null;best=-1;
            for(String id:sensor.getPhysicalCameraIds()){CameraCharacteristics c=m.getCameraCharacteristics(id);SizeF s=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);float area=s==null?0:s.getWidth()*s.getHeight();if(area>best){best=area;physical=id;}}
            if(physical!=null)sensor=m.getCameraCharacteristics(physical);
            Range<Integer> iso=sensor.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);if(iso==null||iso.getLower()!=50)throw new IllegalStateException("主摄最低 ISO 不是50");
            Range<Long> time=sensor.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);minNs=time.getLower();maxNs=Math.min(1_000_000_000L,time.getUpper());
            openedAt=System.currentTimeMillis();exposure=Math.max(minNs,Math.min(maxNs,exposure));int token=generation;opening=true;
            m.openCamera(logical,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice c){opening=false;if(!enabled||token!=generation){c.close();return;}camera=c;configure(token);}
                public void onDisconnected(CameraDevice c){c.close();if(c==camera){camera=null;report("预览已断开");}}
                public void onError(CameraDevice c,int e){c.close();if(token==generation){camera=null;opening=false;report("预览暂不可用 · "+e);}}
            },worker);
        }catch(Exception e){opening=false;if(enabled&&camera==null&&openRetries++<5){int token=generation;worker.postDelayed(()->{if(enabled&&token==generation)open();},300);}else report(e.getMessage());}
    }
    private void configure(int token){try{
        texture.getSurfaceTexture().setDefaultBufferSize(1280,960);surface=new Surface(texture.getSurfaceTexture());
        meter=ImageReader.newInstance(640,480,ImageFormat.YUV_420_888,2);meter.setOnImageAvailableListener(this::measure,worker);
        ArrayList<OutputConfiguration> outputs=new ArrayList<>();
        for(Surface s:Arrays.asList(surface,meter.getSurface())){OutputConfiguration o=new OutputConfiguration(s);if(physical!=null)o.setPhysicalCameraId(physical);outputs.add(o);}
        camera.createCaptureSession(new SessionConfiguration(0,outputs,r->worker.post(r),new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession s){if(!enabled||token!=generation){s.close();return;}session=s;try{repeat();}catch(Exception e){report(e.toString());}}
            public void onConfigureFailed(CameraCaptureSession s){report("主摄预览流被拒绝");close();}
        }));
    }catch(Exception e){report(e.toString());close();}}
    private <T> void set(CaptureRequest.Builder b,CaptureRequest.Key<T> k,T v){b.set(k,v);}
    private CaptureRequest.Builder previewRequest(int trigger)throws Exception{
        CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);b.addTarget(surface);b.addTarget(meter.getSurface());
        set(b,CaptureRequest.CONTROL_AE_MODE,0);set(b,CaptureRequest.SENSOR_SENSITIVITY,50);set(b,CaptureRequest.SENSOR_EXPOSURE_TIME,exposure);
        long maxFrame=sensor.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);set(b,CaptureRequest.SENSOR_FRAME_DURATION,Math.min(maxFrame,Math.max(66_666_667,exposure+2_000_000)));
        set(b,CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,100);set(b,CaptureRequest.CONTROL_AWB_MODE,1);
        set(b,CaptureRequest.CONTROL_AF_MODE,infinity?0:pointFocus?1:4);set(b,CaptureRequest.CONTROL_AF_TRIGGER,trigger);
        if(!infinity&&focusRegions!=null)set(b,CaptureRequest.CONTROL_AF_REGIONS,focusRegions);if(infinity)set(b,CaptureRequest.LENS_FOCUS_DISTANCE,0f);else b.set(CaptureRequest.LENS_FOCUS_DISTANCE,null);
        set(b,CaptureRequest.CONTROL_ZOOM_RATIO,1f);set(b,CaptureRequest.FLASH_MODE,0);set(b,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,autoIso?1:0);
        if(autoIso && auto) {
            set(b,CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            set(b,CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new Range<Integer>(30,30));
        }
        return b;
    }
    private void repeat()throws Exception{if(session==null||camera==null||!enabled)return;
        session.setRepeatingRequest(previewRequest(CaptureRequest.CONTROL_AF_TRIGGER_IDLE).build(),focusCallback,worker);
    }
    private void measure(ImageReader reader){Image image=null;try{image=reader.acquireLatestImage();if(image==null)return;long now=SystemClock.elapsedRealtime();if(now-lastMeter<350)return;lastMeter=now;
        Image.Plane p=image.getPlanes()[0];ByteBuffer bytes=p.getBuffer();int[] h=new int[256];int count=0;
        for(int y=0;y<image.getHeight();y+=4)for(int x=0;x<image.getWidth();x+=4){int i=y*p.getRowStride()+x*p.getPixelStride();if(i<bytes.limit()){h[bytes.get(i)&255]++;count++;}}
        int total=0,q90=0;for(int i=0;i<256;i++){total+=h[i];if(total>=count*.90){q90=i;break;}}
        if(auto&&!autoIso&&actualIso==50){double ratio=Math.pow(185.0/Math.max(8,q90),1.25);ratio=Math.max(.70,Math.min(1.4,ratio));if(Math.abs(q90-185)>6){long next=Math.max(minNs,Math.min(maxNs,Math.round(exposure*ratio)));if(next!=exposure){exposure=next;repeat();}}}
        long t=exposureNs();int iso=actualIso;host.runOnUiThread(()->listener.update(t,iso,h,"预览亮度 · 非 RAW"));
    }catch(Exception e){report(e.toString());}finally{if(image!=null)image.close();}}
    private void close(){generation++;opening=false;if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(meter!=null){meter.close();meter=null;}if(surface!=null){surface.release();surface=null;}actual=0;actualIso=0;focusState=-1;pointFocus=false;focusRegions=null;pointSerial++;if(focusWaiter!=null){focusWaiter.completeExceptionally(new IllegalStateException("预览已关闭"));focusWaiter=null;}}
    public void stopAndWait()throws InterruptedException{enabled=false;CountDownLatch done=new CountDownLatch(1);worker.post(()->{close();done.countDown();});if(!done.await(5,TimeUnit.SECONDS))throw new IllegalStateException("等待主摄预览释放超时");}
    public void stop(){enabled=false;worker.post(this::close);}
    public void destroy(){enabled=false;worker.post(()->{close();thread.quitSafely();});}
}
