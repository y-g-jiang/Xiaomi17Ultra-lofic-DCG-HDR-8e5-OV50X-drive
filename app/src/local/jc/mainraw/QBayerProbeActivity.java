package local.jc.mainraw;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.TextView;
import java.util.Arrays;

/** Diagnostic only: request a full-resolution still stream and log the actual sensor mode. */
public final class QBayerProbeActivity extends Activity {
    private static final String TAG="JCQbayerProbe";
    private static final int WIDTH=8192, HEIGHT=6144;
    private TextView status;
    private HandlerThread thread;
    private Handler worker;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        status=new TextView(this);status.setTextSize(18);status.setPadding(24,50,24,24);
        status.setText("请求主摄 8192×6144 单张；检查传感器实际 mode…");setContentView(status);
        thread=new HandlerThread("QbayerProbe");thread.start();worker=new Handler(thread.getLooper());
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},81);
        }else worker.post(this::start);
    }
    @Override public void onRequestPermissionsResult(int request,String[] names,int[] grants){
        super.onRequestPermissionsResult(request,names,grants);
        if(request==81&&grants.length==1&&grants[0]==PackageManager.PERMISSION_GRANTED)worker.post(this::start);
        else show("需要相机权限");
    }
    private void show(String value){Log.i(TAG,value);runOnUiThread(()->status.setText(value));}
    @SuppressWarnings("MissingPermission") private void start(){
        try{
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
            String chosen=null;
            for(String id:manager.getCameraIdList()){
                CameraCharacteristics c=manager.getCameraCharacteristics(id);
                if(!Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING)))continue;
                StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map==null)continue;
                boolean full=false;for(Size s:map.getOutputSizes(ImageFormat.JPEG))
                    if(s.getWidth()==WIDTH&&s.getHeight()==HEIGHT)full=true;
                Log.i(TAG,"camera="+id+" fullJPEG="+full+" physical="+c.getPhysicalCameraIds());
                if(full&&chosen==null)chosen=id;
            }
            if(chosen==null)throw new IllegalStateException("No back camera exposes 8192x6144 JPEG");
            final String id=chosen;show("选择后摄 ID "+id+"，申请 8192×6144 JPEG 流");
            reader=ImageReader.newInstance(WIDTH,HEIGHT,ImageFormat.JPEG,2);
            reader.setOnImageAvailableListener(r->{Image img=r.acquireNextImage();if(img!=null){
                Log.i(TAG,"image="+img.getWidth()+"x"+img.getHeight()+" timestamp="+img.getTimestamp());img.close();}},worker);
            manager.openCamera(id,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice device){camera=device;configure();}
                @Override public void onDisconnected(CameraDevice device){show("相机断开");device.close();}
                @Override public void onError(CameraDevice device,int error){show("相机错误 "+error);device.close();}
            },worker);
        }catch(Exception e){show("启动失败 "+e);Log.e(TAG,"start",e);}
    }
    private void configure(){
        try{
            Surface surface=reader.getSurface();
            camera.createCaptureSession(Arrays.asList(surface),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession s){session=s;capture();}
                @Override public void onConfigureFailed(CameraCaptureSession s){show("8192×6144 流配置失败");}
            },worker);
        }catch(Exception e){show("配置异常 "+e);Log.e(TAG,"configure",e);}
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private void capture(){
        try{
            CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY,50);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,10000000L);
            b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE,0f);
            session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){
                @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){
                    StringBuilder report=new StringBuilder("8192×6144 JPEG 请求完成\nISO="+result.get(CaptureResult.SENSOR_SENSITIVITY)+" t="+result.get(CaptureResult.SENSOR_EXPOSURE_TIME)+"\n");
                    for(CaptureResult.Key key:result.getKeys()){
                        String name=key.getName();
                        if(name.contains("SensorCurrentMode")||name.contains("mode_index")||name.contains("current_mode")||name.contains("remosaic")||name.contains("DCGMode"))
                            report.append(name).append("=").append(result.get(key)).append("\n");
                    }
                    show(report.toString());
                }
                @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure failure){show("拍摄失败 "+failure.getReason());}
            },worker);
        }catch(Exception e){show("请求异常 "+e);Log.e(TAG,"capture",e);}
    }
    @Override public void onDestroy(){super.onDestroy();if(session!=null)session.close();if(camera!=null)camera.close();if(reader!=null)reader.close();if(thread!=null)thread.quitSafely();}
}
