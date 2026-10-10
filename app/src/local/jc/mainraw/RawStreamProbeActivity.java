package local.jc.mainraw;
import android.app.Activity;
import android.os.*;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.util.*;
import android.view.Surface;
import android.widget.TextView;
import java.util.*;
import java.io.*;
import java.nio.*;
import org.json.*;

/** Bounded evidence probe. Stream format alone never proves LOFIC or QBayer. */
public final class RawStreamProbeActivity extends Activity {
    private HandlerThread thread;private Handler worker;private CameraDevice device;private CameraCaptureSession session;
    private final ArrayList<ImageReader> readers=new ArrayList<>();private File dir;private JSONObject report=new JSONObject();
    private String logical,physical;private CameraCharacteristics sensor;private TextView status;private int requestedMode,width,height,received;
    private boolean ended;private int discoveryTries;private final ArrayList<JSONObject> frames=new ArrayList<>();
    public void onCreate(Bundle s){super.onCreate(s);status=new TextView(this);status.setText("原始流诊断 · 不修改寄存器");status.setTextSize(20);setContentView(status);
        dir=new File(getFilesDir(),"raw_stream_probe/"+System.currentTimeMillis());dir.mkdirs();
        requestedMode=getIntent().getIntExtra("mode",5);
        // Mode 11 is advertised as 8192x6144, but this HAL may only publish
        // a legal 4096x3072 RAW stream.  Use the published stream size for
        // the configuration while retaining the requested mode in metadata.
        width=requestedMode==0?8192:4096;height=width*3/4;
        thread=new HandlerThread("JC-RawStreamProbe");thread.start();worker=new Handler(thread.getLooper());worker.post(this::open);worker.postDelayed(()->finishProbe("bounded_timeout"),18000);
    }
    private void save(){try{try(FileOutputStream o=new FileOutputStream(new File(dir,"report.json"))){o.write(report.toString(2).getBytes("UTF-8"));}}catch(Exception e){android.util.Log.e("JCRawProbe","report",e);}}
    private void event(String name,Object v){try{report.put(name,v);save();}catch(Exception ignored){}android.util.Log.i("JCRawProbe",name+"="+v);}
    private void finishProbe(String reason){if(ended)return;ended=true;event("terminal",reason);event("received",received);if(session!=null){session.close();session=null;}if(device!=null){device.close();device=null;}for(ImageReader r:readers)r.close();readers.clear();runOnUiThread(()->status.setText("原始流诊断结束："+reason+"；实际缓冲 "+received+" 份"));}
    @SuppressWarnings("MissingPermission") private void open(){try{
        if(NativePhotoBridge.WORKING.get())throw new IllegalStateException("native capture active");
        if(!new NativePhotoBridge(this).checkRoot().contains("没有待处理会话"))throw new IllegalStateException("pending native session");
        CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);float best=-1;
        for(String id:manager.getCameraIdList()){CameraCharacteristics c=manager.getCameraCharacteristics(id);SizeF sz=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);if(Integer.valueOf(1).equals(c.get(CameraCharacteristics.LENS_FACING))&&sz!=null&&sz.getWidth()*sz.getHeight()>best){best=sz.getWidth()*sz.getHeight();logical=id;sensor=c;}}
        if(sensor==null){if(++discoveryTries<8){worker.postDelayed(this::open,1000);return;}throw new IllegalStateException("Camera enumeration did not become ready");}
        best=-1;for(String id:sensor.getPhysicalCameraIds()){CameraCharacteristics c=manager.getCameraCharacteristics(id);SizeF sz=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);if(sz!=null&&sz.getWidth()*sz.getHeight()>best){best=sz.getWidth()*sz.getHeight();physical=id;}}
        if(physical!=null)sensor=manager.getCameraCharacteristics(physical);
        event("logicalCamera",logical);event("physicalCamera",physical);event("requestedSensorMode",requestedMode);
        event("requestedDimensions",requestedMode==11?"8192x6144":width+"x"+height);
        event("dimensions",width+"x"+height);event("requestedISO",50);event("requestedExposureNs",1_000_000);
        JSONArray config=new JSONArray();StreamConfigurationMap map=sensor.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        for(int format:new int[]{ImageFormat.RAW_SENSOR,ImageFormat.RAW10}){Size[] sizes=map.getOutputSizes(format);JSONObject j=new JSONObject().put("format",format);JSONArray a=new JSONArray();if(sizes!=null)for(Size z:sizes)a.put(z.toString());j.put("sizes",a);config.put(j);}
        event("publishedRawOutputs",config);event("maxRawStreams",sensor.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW));
        JSONArray keys=new JSONArray();for(CaptureRequest.Key<?> k:sensor.getAvailableCaptureRequestKeys())if(k.getName().contains("mode")||k.getName().contains("HDR")||k.getName().contains("Lofic"))keys.put(k.getName());event("modeRequestKeys",keys);
        manager.openCamera(logical,new CameraDevice.StateCallback(){
            public void onOpened(CameraDevice c){if(ended){c.close();return;}device=c;configure();}
            public void onDisconnected(CameraDevice c){c.close();finishProbe("disconnected");}
            public void onError(CameraDevice c,int e){c.close();finishProbe("camera_error_"+e);}
        },worker);
    }catch(Exception e){event("exception",e.toString());finishProbe("open_failed");}}
    private void configure(){try{
        boolean dual=getIntent().getBooleanExtra("dual",requestedMode==5);int primary=getIntent().getIntExtra("format",ImageFormat.RAW_SENSOR);int[] formats=dual?new int[]{ImageFormat.RAW_SENSOR,ImageFormat.RAW10}:new int[]{primary};
        event("dualRequested",dual);ArrayList<OutputConfiguration> outputs=new ArrayList<>();
        for(int f:formats){ImageReader r=ImageReader.newInstance(width,height,f,2);readers.add(r);r.setOnImageAvailableListener(this::image,worker);OutputConfiguration o=new OutputConfiguration(r.getSurface());if(physical!=null)o.setPhysicalCameraId(physical);outputs.add(o);}
        SessionConfiguration sc=new SessionConfiguration(0,outputs,r->worker.post(r),new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession c){if(ended){c.close();return;}session=c;event("configured",true);shoot();}
            public void onConfigureFailed(CameraCaptureSession c){finishProbe("stream_configuration_rejected");}
        });
        CaptureRequest.Builder params=request();sc.setSessionParameters(params.build());device.createCaptureSession(sc);
    }catch(Exception e){event("exception",e.toString());finishProbe("configure_exception");}}
    private CaptureRequest.Builder request()throws Exception {
        CaptureRequest.Builder b=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        b.set(CaptureRequest.CONTROL_AE_MODE,0);b.set(CaptureRequest.SENSOR_SENSITIVITY,50);b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,1_000_000L);b.set(CaptureRequest.SENSOR_FRAME_DURATION,100_000_000L);
        b.set(CaptureRequest.CONTROL_AF_MODE,0);b.set(CaptureRequest.LENS_FOCUS_DISTANCE,0f);b.set(CaptureRequest.FLASH_MODE,0);b.set(CaptureRequest.CONTROL_ZOOM_RATIO,1f);
        b.set(new CaptureRequest.Key<int[]>("org.codeaurora.qcamera3.sensor_meta_data.current_mode",int[].class),new int[]{requestedMode});
        if(requestedMode==5&&getIntent().getBooleanExtra("hdr",false)){
            b.set(new CaptureRequest.Key<int[]>(HdrMode.DCG,int[].class),new int[]{HdrMode.pack(5,14,10)});
            b.set(new CaptureRequest.Key<int[]>(HdrMode.EXPOSURES,int[].class),new int[]{2});
        }
        return b;
    }
    private void shoot(){try{CaptureRequest.Builder b=request();for(ImageReader r:readers)b.addTarget(r.getSurface());
        session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){
            public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest q,TotalCaptureResult all){
                try{CaptureResult actual=physical==null?all:all.getPhysicalCameraResults().get(physical);if(actual==null)actual=all;JSONObject meta=new JSONObject();
                    meta.put("timestampNs",actual.get(CaptureResult.SENSOR_TIMESTAMP));meta.put("ISO",actual.get(CaptureResult.SENSOR_SENSITIVITY));meta.put("exposureNs",actual.get(CaptureResult.SENSOR_EXPOSURE_TIME));
                    for(CaptureResult.Key<?> k:actual.getKeys())if(k.getName().contains("current_mode")||k.getName().contains("SensorCurrentMode")||k.getName().contains("DCG")||k.getName().contains("Lofic")||k.getName().contains("remosaic"))meta.put(k.getName(),JSONObject.wrap(actual.get(k)));
                    event("actual",meta);event("semanticStatus","Unverified branch/CFA: do not label buffers as LOFIC or Quad Bayer without source evidence");
                }catch(Exception e){event("metadata_error",e.toString());}
            }
            public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f){event("captureFailureReason",f.getReason());finishProbe("capture_failed");}
        },worker);
    }catch(Exception e){event("exception",e.toString());finishProbe("request_rejected");}}
    private void image(ImageReader r){Image im=null;try{im=r.acquireNextImage();if(im==null||ended)return;Image.Plane p=im.getPlanes()[0];ByteBuffer b=p.getBuffer();int n=b.remaining();String file="stream_"+im.getFormat()+"_"+im.getTimestamp()+".bin";
        try(FileOutputStream o=new FileOutputStream(new File(dir,file))){byte[] chunk=new byte[65536];while(b.hasRemaining()){int k=Math.min(chunk.length,b.remaining());b.get(chunk,0,k);o.write(chunk,0,k);}}
        frames.add(new JSONObject().put("file",file).put("width",im.getWidth()).put("height",im.getHeight()).put("format",im.getFormat()).put("timestampNs",im.getTimestamp()).put("rowStride",p.getRowStride()).put("pixelStride",p.getPixelStride()).put("bytes",n));
        received++;event("frames",new JSONArray(frames));if(received==readers.size())worker.postDelayed(()->finishProbe("buffers_received"),1000);
    }catch(Exception e){event("image_error",e.toString());finishProbe("image_failed");}finally{if(im!=null)im.close();}}
    public void onDestroy(){super.onDestroy();worker.post(()->{finishProbe("activity_destroyed");thread.quitSafely();});}
}
