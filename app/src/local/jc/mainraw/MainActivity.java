package local.jc.mainraw;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.Array;
import java.nio.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Main-sensor RAW probe with capability-gated OEM LOFIC/DCG session controls. */
public class MainActivity extends Activity {
    private LinearLayout form;
    private TextureView preview;
    private TextView status, info, hdrInfo;
    private Button capture, open, apply;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final Map<String, CheckBox> flags = new LinkedHashMap<>();
    private final Map<String, Spinner> choices = new LinkedHashMap<>();
    private final Map<String, CaptureRequest.Key<?>> requestKeys = new TreeMap<>();
    private final Set<String> physicalKeys = new HashSet<>();
    private final Set<String> sessionKeys = new HashSet<>();
    private List<HdrMode> hdrModes = new ArrayList<>();
    private int[] mainHdrCaps;
    private JSONObject submittedSession = new JSONObject();
    private volatile boolean hdrSessionAccepted;
    private CameraManager manager;
    private CameraCharacteristics logical, sensor;
    private String logicalId, physicalId;
    private HandlerThread thread;
    private Handler worker;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader rawReader;
    private Surface previewSurface;
    private final Map<Long, Image> images = new HashMap<>();
    private final Map<Long, TotalCaptureResult> results = new HashMap<>();
    private JSONObject settings, batchSettings;
    private JSONArray brackets;
    private Size[] rawSizes;
    private volatile int remaining;
    private int sequence;
    private volatile boolean busy, opening, resumed;
    private volatile int generation;
    private String batch;
    private long lastLive;
    private final List<Uri> pendingUris = new ArrayList<>();
    private Runnable captureTimeout;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        thread = new HandlerThread("JCRawCamera"); thread.start(); worker = new Handler(thread.getLooper());
        manager = (CameraManager)getSystemService(CAMERA_SERVICE);
        ScrollView scroll = new ScrollView(this); form = new LinearLayout(this); form.setOrientation(1);
        form.setPadding(dp(16),dp(16),dp(16),dp(24)); scroll.addView(form); setContentView(scroll);
        label("JC Camera2 参数草稿 · 0.3 / ISO 50 固定",22);
        label("仅主摄 · LOFIC / DCG HDR 会话实验\n按手机公开能力发送厂商参数。模式请求不等于硬件已启用，16 位 RAW 容器不等于 16 位有效信号。",14);
        info = label("读取主摄能力…",14);
        spinner("hdrProfile","传感器模式（更改后须应用参数）",new String[]{"读取中"});
        hdrInfo=label("读取主摄 LOFIC / DCG HDR 能力…",13);
        button("应用模式 / 重建预览",v->rebuildPreview());
        button("两个原生 Photo 包的配置参考",v->showReference());
        preview = new TextureView(this); form.addView(preview,new LinearLayout.LayoutParams(-1,dp(220)));
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h) {transformPreview();}
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h) {transformPreview();}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {closeCamera();return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture t) {}
        });
        status = label("尚未打开相机。拍摄和解锁均由你操作。",14);
        open = button("打开主摄预览",v->openCamera());
        capture = button("拍摄 DNG ＋ 参数记录",v->startCapture()); capture.setEnabled(false);
        button("停止连续拍摄",v->{ remaining=0; message("已请求停止；正在写入的一帧会正常完成。"); });
        label("曝光 / 对焦 / 白平衡",19);
        flag("autoExposure","自动曝光已禁用：最低 ISO 固定",false);
        field("iso","ISO 固定为最低 50，不可更改","50",false);
        flags.get("autoExposure").setEnabled(false);fields.get("iso").setEnabled(false);
        field("exposureMs","曝光时间 ms","10",false);
        field("frameMs","帧时长 ms，0 = 根据曝光自动计算","0",false);
        field("evComp","自动曝光补偿档位索引（范围见能力列表）","0",false);
        field("fps","自动曝光 FPS 范围，例 15,30；留空使用默认","",false);
        flag("aeLock","自动曝光锁定",false);
        flag("autoFocus","连续自动对焦",true);
        field("focus","手动焦距（屈光度，0 = 无穷远）","0",false);
        flag("autoWhiteBalance","自动白平衡",true);
        flag("awbLock","自动白平衡锁定",false);
        field("wbGains","手动白平衡 R,Ge,Go,B 增益","2,1,1,1.5",false);
        field("colorMatrix","手动色彩矩阵（9 个数；留空采用单位矩阵）","",false);
        flag("ois","光学防抖 OIS",false);
        label("数据输出 / 连拍",19);
        spinner("size","RAW 输出尺寸",new String[]{"读取中"});
        field("frames","连续保存帧数（1–32；逐帧，不是 LOFIC 合成）","1",false);
        field("intervalMs","帧间额外等待 ms（写完上一帧后）","0",false);
        field("bracketEV","手动曝光包围 EV，例如 0,-2,0,-2；留空不包围","",false);
        flag("saveRaw16","另存去行填充的 .raw16 原始缓冲区",false);
        spinner("noiseReduction","降噪模式（仅列出设备支持值）",new String[]{"默认"});
        spinner("edge","锐化模式（仅列出设备支持值）",new String[]{"默认"});
        spinner("hotPixel","坏点处理模式",new String[]{"默认"});
        spinner("shading","阴影校正模式",new String[]{"默认"});
        spinner("aberration","色差校正模式",new String[]{"默认"});
        spinner("distortion","几何畸变校正模式",new String[]{"默认"});
        label("高级请求参数",19);
        label("打开“能力与可控参数”查看系统公开的键。会话键仅填入下方会话 JSON；非默认 HDR 预设管理的三个键不可重复覆盖。主摄锁定、变焦 1× 和完整裁剪区域不可覆盖。只读能力不是可写参数。",13);
        field("advanced","每次请求参数 JSON","{}",true);
        field("session","会话参数 JSON（使用“应用参数”后重建会话）","{}",true);
        label("例：{\"android.control.aeAntibandingMode\":{\"type\":\"int\",\"value\":3}}\n支持 int / long / float / double / byte / boolean 及其数值数组、Rational、Rational[]、Rect、Size、Range<Integer>、MeteringRectangle[]、RggbChannelVector、ColorSpaceTransform、TonemapCurve、Location。",12);
        apply=button("应用参数 / 重建预览",v->rebuildPreview());
        button("能力与可控参数 / 导出",v->showCapabilities());
        button("恢复界面默认值",v->{if(busy){message("请先等待当前帧写入完成。");return;}getPreferences(0).edit().clear().apply();recreate();});
        label("输出：下载 / JCCamera。每张 DNG 配套 JSON，包含请求、实际返回值、白/黑电平和主摄身份；高位深 LOFIC 的真实性须另行验证。界面不申请 Root，不修改系统相机文件。",13);
        try {discover(); restore();flags.get("autoExposure").setChecked(false);fields.get("iso").setText("50");} catch(Exception e) {error(e);open.setEnabled(false);}
    }
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n);}
    private TextView label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,dp(8),0,dp(6));t.setTextIsSelectable(true);form.addView(t);return t;}
    private EditText field(String key,String name,String initial,boolean multi){label(name,13);EditText e=new EditText(this);e.setText(initial);e.setTextSize(14);e.setSingleLine(!multi);if(multi){e.setMinLines(3);e.setTypeface(Typeface.MONOSPACE);}form.addView(e);fields.put(key,e);return e;}
    private void flag(String key,String title,boolean checked){CheckBox c=new CheckBox(this);c.setText(title);c.setChecked(checked);form.addView(c);flags.put(key,c);}
    private Button button(String title,View.OnClickListener action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(action);form.addView(b);return b;}
    private void spinner(String key,String title,String[] labels){label(title,13);Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));form.addView(s);choices.put(key,s);}
    private void populate(String key,int[] values){ArrayList<String> a=new ArrayList<>();a.add("默认");if(values!=null)for(int v:values)a.add(Integer.toString(v));choices.get(key).setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,a));}
    private void message(String s){runOnUiThread(()->status.setText(s));}
    private void error(Exception e){android.util.Log.e("JCRaw",e.toString(),e);message("错误："+e.getClass().getSimpleName()+" · "+e.getMessage());}
    private void rebuildPreview(){if(busy){message("请先停止并等待当前拍摄结束。");return;}try{settings=readSettings();getPreferences(0).edit().putString("settings",settings.toString()).apply();closeCamera();worker.post(()->runOnUiThread(()->openCamera()));}catch(Exception e){error(e);}}

    private void discover() throws Exception {
        // Choose the public back logical camera with largest physical sensor area.
        float largest=-1;
        for(String id:manager.getCameraIdList()){
            CameraCharacteristics c=manager.getCameraCharacteristics(id);
            if(!Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING)))continue;
            SizeF sz=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float area=sz==null?0:sz.getWidth()*sz.getHeight();
            if(area>largest){largest=area;logicalId=id;logical=c;}
        }
        if(logical==null)throw new IllegalStateException("没有可访问的后置相机");
        sensor=logical; physicalId=null; largest=-1;
        for(String id:logical.getPhysicalCameraIds()){
            CameraCharacteristics c=manager.getCameraCharacteristics(id);SizeF sz=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float area=sz==null?0:sz.getWidth()*sz.getHeight();
            if(area>largest){largest=area;physicalId=id;sensor=c;}
        }
        StreamConfigurationMap map=sensor.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        rawSizes=map.getOutputSizes(ImageFormat.RAW_SENSOR);
        if(rawSizes==null||rawSizes.length==0)throw new IllegalStateException("主摄未开放 RAW_SENSOR，不能导出 DNG");
        Arrays.sort(rawSizes,Comparator.comparingLong(s->(long)s.getWidth()*s.getHeight()));
        String[] labels=new String[rawSizes.length];int best=0;
        for(int i=0;i<labels.length;i++){labels[i]=rawSizes[i].toString();if((long)rawSizes[i].getWidth()*rawSizes[i].getHeight()<=4096L*3072)best=i;}
        choices.get("size").setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));choices.get("size").setSelection(best);
        for(CaptureRequest.Key<?> k:logical.getAvailableCaptureRequestKeys())requestKeys.put(k.getName(),k);
        List<CaptureRequest.Key<?>> pk=logical.getAvailablePhysicalCameraRequestKeys();if(pk!=null)for(CaptureRequest.Key<?> k:pk)physicalKeys.add(k.getName());
        List<CaptureRequest.Key<?>> sk=logical.getAvailableSessionKeys();if(sk!=null)for(CaptureRequest.Key<?> k:sk)sessionKeys.add(k.getName());
        mainHdrCaps=vendorInts(sensor,HdrMode.CAPS);
        hdrModes=HdrMode.supported(requestKeys.keySet(),sessionKeys,mainHdrCaps);
        ArrayList<String> hdrLabels=new ArrayList<>();for(HdrMode h:hdrModes)hdrLabels.add(h.label);
        choices.get("hdrProfile").setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,hdrLabels));
        choices.get("hdrProfile").setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){updateHdrInfo();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        populate("noiseReduction",sensor.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES));
        populate("edge",sensor.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES));populate("hotPixel",sensor.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES));
        populate("shading",sensor.get(CameraCharacteristics.SHADING_AVAILABLE_MODES));populate("aberration",sensor.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES));
        populate("distortion",sensor.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES));
        info.setText("逻辑相机 "+logicalId+" → 主摄物理 ID "+(physicalId==null?logicalId:physicalId)+"（输出固定绑定）\n焦距 "+value(sensor.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))+" mm；光圈 "+value(sensor.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES))+"\nISO "+sensor.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)+"；曝光 ns "+sensor.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)+"\nRAW_SENSOR / 16 位存储；静态白电平 "+sensor.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
    }
    private JSONObject readSettings() throws Exception {
        JSONObject j=new JSONObject();for(String k:fields.keySet())j.put(k,fields.get(k).getText().toString().trim());
        for(String k:flags.keySet())j.put(k,flags.get(k).isChecked());for(String k:choices.keySet())j.put(k,choices.get(k).getSelectedItem().toString());
        j.put("autoExposure",false);j.put("iso","50");
        if(sensor.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).getLower()!=50)throw new IllegalStateException("当前主摄最低 ISO 不是已验证的 50，拒绝拍摄");
        new JSONObject(j.getString("advanced"));new JSONObject(j.getString("session"));
        HdrMode mode=hdrModes.get(choices.get("hdrProfile").getSelectedItemPosition());j.put("hdrProfileId",mode.id);
        JSONObject extra=new JSONObject(j.getString("advanced")), sess=new JSONObject(j.getString("session"));
        mode.rejectConflicts(jsonKeys(extra));mode.rejectConflicts(jsonKeys(sess));
        for(String key:jsonKeys(extra))if(sessionKeys.contains(key))throw new IllegalArgumentException("会话键请移至会话参数 JSON 并应用参数："+key);
        int n=Integer.parseInt(j.getString("frames"));if(n<1||n>32)throw new IllegalArgumentException("连续帧数须为 1–32");
        double interval=number(j,"intervalMs");if(!Double.isFinite(interval)||interval<0||interval>60000)throw new IllegalArgumentException("等待须为 0–60000 ms");
        return j;
    }
    private void restore() throws Exception {String s=getPreferences(0).getString("settings",null);if(s==null)return;JSONObject j=new JSONObject(s);for(String k:fields.keySet())if(j.has(k))fields.get(k).setText(j.getString(k));for(String k:flags.keySet())if(j.has(k))flags.get(k).setChecked(j.getBoolean(k));for(String k:choices.keySet())for(int i=0;i<choices.get(k).getCount();i++)if(choices.get(k).getItemAtPosition(i).toString().equals(j.optString(k)))choices.get(k).setSelection(i);}
    private double number(JSONObject j,String k)throws Exception{return Double.parseDouble(j.getString(k));}
    private static Set<String> jsonKeys(JSONObject j){Set<String> keys=new HashSet<>();for(Iterator<String> it=j.keys();it.hasNext();)keys.add(it.next());return keys;}
    private int[] vendorInts(CameraCharacteristics c,String name){
        for(CameraCharacteristics.Key<?> k:c.getKeys())if(k.getName().equals(name)){
            Object v=c.get(k);if(v instanceof int[])return ((int[])v).clone();if(v instanceof Number)return new int[]{((Number)v).intValue()};
        }
        // Some HALs omit vendor static keys from availableCharacteristicsKeys while the data remain readable.
        try{return c.get(new CameraCharacteristics.Key<int[]>(name,int[].class));}
        catch(IllegalArgumentException e){android.util.Log.w("JCRaw","Vendor static capability unavailable: "+name,e);return null;}
    }
    private HdrMode profile(JSONObject s)throws Exception{
        String id=s.getString("hdrProfileId");for(HdrMode h:hdrModes)if(h.id.equals(id))return h;
        throw new IllegalArgumentException("当前主摄不支持保存的 HDR 模式："+id);
    }
    private void applyHdr(CaptureRequest.Builder b,HdrMode h){
        for(Map.Entry<String,Integer> e:h.parameters.entrySet()){
            String name=e.getKey();if(!requestKeys.containsKey(name)||!sessionKeys.contains(name))throw new IllegalArgumentException("设备没有公开厂商会话键："+name);
            // OEM int32 metadata are arrays in Camera2. Set one native int32, not a guessed boolean.
            put(b,new CaptureRequest.Key<int[]>(name,int[].class),new int[]{e.getValue()});
        }
    }
    private JSONObject requestJson(CaptureRequest r)throws Exception{JSONObject j=new JSONObject();for(CaptureRequest.Key<?> k:r.getKeys())j.put(k.getName(),value(r.get(k)));return j;}
    private JSONObject modeJson(HdrMode h)throws Exception{
        JSONObject j=new JSONObject();j.put("id",h.id);j.put("label",h.label);j.put("parametersInt32",new JSONObject(h.parameters));
        if(h.parameters.containsKey(HdrMode.DCG)){j.put("packedDcgWord",h.parameters.get(HdrMode.DCG));j.put("mode",h.mode);j.put("longChannelBitsRequested",h.longBits);j.put("shortChannelBitsRequested",h.shortBits);}
        return j;
    }
    private JSONObject hdrReport(JSONObject s)throws Exception{
        JSONObject j=modeJson(profile(s));j.put("mainPhysicalAdvertisedDcgModes",value(mainHdrCaps));j.put("capabilitySourceCameraId",physicalId==null?logicalId:physicalId);
        j.put("submittedSessionParameters",submittedSession);j.put("sessionConfigured",hdrSessionAccepted);
        j.put("hardwareActivationVerified",false);j.put("effectiveSignalBitsVerified",false);j.put("stockPhotoV5V9FusionInvoked",false);
        j.put("manualExposureMayDowngradeLofic",!s.getBoolean("autoExposure"));
        j.put("verificationNote","Session acceptance and RAW sample range do not verify sensor mode or effective precision. Compare actual/logicalActual metadata and vendor HAL logs.");return j;
    }
    private void updateHdrInfo(){try{
        if(hdrModes.isEmpty())return;HdrMode h=hdrModes.get(choices.get("hdrProfile").getSelectedItemPosition());
        boolean active=hdrSessionAccepted&&settings!=null&&h.id.equals(settings.optString("hdrProfileId"));
        hdrInfo.setText(h.label+"\n"+(active?"会话已建立；硬件模式未验证。":"尚未应用；打开相机或应用参数后生效。")+
            "\n主摄公布的 DCG 组合："+value(mainHdrCaps)+
            (h.isDefault()?"\n默认使用原厂模板；下方高级会话 JSON 可用于单独实验。":"\n请求参数："+h.parameters)+
            "\n14+10 表示两个通道的请求规格，不是 24 位输出。本版本强制 ISO50；此 Camera2 通道可能被原厂策略降级，不冒充原生双支路输出。");
    }catch(Exception e){error(e);}}
    private JSONObject moduleReference()throws Exception{
        try(InputStream in=getAssets().open("module_reference.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return new JSONObject(new String(out.toByteArray(),"UTF-8"));
        }
    }
    private void showReference(){try{
        JSONObject reference=moduleReference();String text="Photo 包选择 SE V5；HDR 包选择 HDR V9。两者都是原生 Photo 八帧路线，sensor_mode 原值均为 17。\n\n本应用接入公开的 LOFIC / DCG 会话键，不等于已调用 V5/V9 的多帧融合。sensor_mode 17 与 DCG 模式 4/5 属于不同枚举；ev_value 的 -3 也不能直接当 -3 EV。\n\n"+reference.toString(2);
        TextView t=new TextView(this);t.setText(text);t.setTextIsSelectable(true);t.setPadding(dp(12),dp(12),dp(12),dp(12));ScrollView s=new ScrollView(this);s.addView(t);
        new AlertDialog.Builder(this).setTitle("原生包参考 / 来源").setView(s).setNegativeButton("关闭",null).show();
    }catch(Exception e){error(e);}}
    private void transformPreview(){if(preview==null||preview.getSurfaceTexture()==null)return;Matrix m=new Matrix();float w=preview.getWidth(),h=preview.getHeight();RectF view=new RectF(0,0,w,h),buffer=new RectF(0,0,720,1280);buffer.offset(view.centerX()-buffer.centerX(),view.centerY()-buffer.centerY());m.setRectToRect(view,buffer,Matrix.ScaleToFit.CENTER);m.postRotate(90,view.centerX(),view.centerY());preview.setTransform(m);}

    private void openCamera(){
        if(opening||device!=null)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},1);return;}
        if(!preview.isAvailable()){message("预览还未就绪，请稍后打开。");return;}
        try{settings=readSettings();opening=true;open.setEnabled(false);int token=generation;manager.openCamera(logicalId,new CameraDevice.StateCallback(){
            public void onOpened(CameraDevice c){opening=false;if(!resumed||token!=generation){c.close();runOnUiThread(()->open.setEnabled(true));message("授权或界面状态已变化，请重新打开主摄预览。");return;}device=c;configure();}
            public void onDisconnected(CameraDevice c){c.close();device=null;opening=false;fail("相机连接中断");}
            public void onError(CameraDevice c,int code){c.close();device=null;opening=false;fail("相机错误 "+code);}
        },worker);}catch(Exception e){opening=false;open.setEnabled(true);error(e);}
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] grants){super.onRequestPermissionsResult(request,p,grants);if(request==1&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)recreate();else message("需要相机权限才能拍摄。");}
    private void configure(){try{
        hdrSessionAccepted=false;final JSONObject configuredSettings=settings;
        Size size=Size.parseSize(settings.getString("size"));rawReader=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.RAW_SENSOR,3);
        rawReader.setOnImageAvailableListener(r->{try{Image im=r.acquireNextImage();if(im!=null){if(!busy){im.close();return;}images.put(im.getTimestamp(),im);pair();}}catch(Exception e){fail(e.toString());}},worker);
        preview.getSurfaceTexture().setDefaultBufferSize(1280,720);previewSurface=new Surface(preview.getSurfaceTexture());
        List<OutputConfiguration> outputs=new ArrayList<>();for(Surface s:Arrays.asList(previewSurface,rawReader.getSurface())){OutputConfiguration oc=new OutputConfiguration(s);if(physicalId!=null)oc.setPhysicalCameraId(physicalId);outputs.add(oc);}
        int token=generation;
        SessionConfiguration sc=new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,r->worker.post(r),new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession c){if(token!=generation||device==null){c.close();return;}session=c;try{CaptureRequest.Builder b=makeRequest(configuredSettings,false,0);session.setRepeatingRequest(b.build(),live,worker);hdrSessionAccepted=true;runOnUiThread(()->{capture.setEnabled(true);open.setEnabled(true);updateHdrInfo();});message("主摄已固定绑定，模式参数已提交、会话已建立。LOFIC / DCG 硬件状态尚未验证。");}catch(Exception e){hdrSessionAccepted=false;fail(e.toString());}}
            public void onConfigureFailed(CameraCaptureSession c){if(token!=generation)return;hdrSessionAccepted=false;fail("所选模式与主摄 RAW 输出组合被 HAL 拒绝；请选择原厂默认或较小 RAW 尺寸，再应用参数。");}
        });
        CaptureRequest.Builder param=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);applyAdvanced(param,new JSONObject(configuredSettings.getString("session")),true);applyHdr(param,profile(configuredSettings));CaptureRequest params=param.build();submittedSession=requestJson(params);sc.setSessionParameters(params);device.createCaptureSession(sc);
    }catch(Exception e){fail(e.toString());}}
    private <T> void put(CaptureRequest.Builder b,CaptureRequest.Key<T> k,T v){if(!requestKeys.containsKey(k.getName()))throw new IllegalArgumentException("设备不支持此请求参数："+k.getName());b.set(k,v);if(physicalId!=null&&physicalKeys.contains(k.getName()))b.setPhysicalCameraKey(k,v,physicalId);}
    private CaptureRequest.Builder makeRequest(JSONObject s,boolean raw,double ev)throws Exception{
        CaptureRequest.Builder b=device.createCaptureRequest(raw?CameraDevice.TEMPLATE_STILL_CAPTURE:CameraDevice.TEMPLATE_PREVIEW);b.addTarget(previewSurface);if(raw)b.addTarget(rawReader.getSurface());
        put(b,CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
        put(b,CaptureRequest.CONTROL_ZOOM_RATIO,1f);put(b,CaptureRequest.SCALER_CROP_REGION,sensor.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
        put(b,CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);
        boolean ae=false;put(b,CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
        if(ae){int ec=Integer.parseInt(s.getString("evComp"));Range<Integer> er=sensor.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);if(er!=null&&!er.contains(ec))throw new IllegalArgumentException("曝光补偿索引超出 "+er);put(b,CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,ec);put(b,CaptureRequest.CONTROL_AE_LOCK,s.getBoolean("aeLock"));String fps=s.getString("fps");if(!fps.isEmpty()){String[] f=fps.split(",");Range<Integer> want=new Range<>(Integer.parseInt(f[0].trim()),Integer.parseInt(f[1].trim()));Range<Integer>[] valid=sensor.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);if(valid==null||!Arrays.asList(valid).contains(want))throw new IllegalArgumentException("未公布的 FPS 范围："+want);put(b,CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,want);}}
        else{int iso=Integer.parseInt(s.getString("iso"));double em=number(s,"exposureMs")*Math.pow(2,ev);if(!Double.isFinite(em)||em<=0)throw new IllegalArgumentException("曝光时间须为正数");long ns=Math.round(em*1e6);Range<Integer> ir=sensor.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);Range<Long> tr=sensor.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);if(!ir.contains(iso)||!tr.contains(ns))throw new IllegalArgumentException("ISO/曝光超出设备范围 "+ir+" / "+tr);long fm=Math.round(number(s,"frameMs")*1e6);long max=sensor.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);if(fm==0)fm=Math.min(max,Math.max(33333333,ns+1000000));if(fm<ns||fm>max)throw new IllegalArgumentException("帧时长必须不小于曝光且不超过 "+max+" ns");put(b,CaptureRequest.SENSOR_SENSITIVITY,iso);put(b,CaptureRequest.SENSOR_EXPOSURE_TIME,ns);put(b,CaptureRequest.SENSOR_FRAME_DURATION,fm);}
        if(s.getBoolean("autoFocus"))put(b,CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        else{float f=(float)number(s,"focus");Float max=sensor.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);if(!Float.isFinite(f)||f<0||max==null||f>max)throw new IllegalArgumentException("对焦范围 0–"+max);put(b,CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);put(b,CaptureRequest.LENS_FOCUS_DISTANCE,f);}
        put(b,CaptureRequest.CONTROL_AWB_MODE,s.getBoolean("autoWhiteBalance")?CaptureRequest.CONTROL_AWB_MODE_AUTO:CaptureRequest.CONTROL_AWB_MODE_OFF);
        put(b,CaptureRequest.CONTROL_AWB_LOCK,s.getBoolean("awbLock"));
        if(!s.getBoolean("autoWhiteBalance")){float[] g=floats(s.getString("wbGains"));if(g.length!=4)throw new IllegalArgumentException("需要四个白平衡增益");for(float f:g)if(!Float.isFinite(f)||f<=0)throw new IllegalArgumentException("白平衡增益须大于零");put(b,CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);put(b,CaptureRequest.COLOR_CORRECTION_GAINS,new RggbChannelVector(g[0],g[1],g[2],g[3]));String mat=s.getString("colorMatrix");float[] a=mat.isEmpty()?new float[]{1,0,0,0,1,0,0,0,1}:floats(mat);if(a.length!=9)throw new IllegalArgumentException("色彩矩阵需 9 个数");Rational[] rr=new Rational[9];for(int i=0;i<9;i++)rr[i]=new Rational(Math.round(a[i]*100000),100000);put(b,CaptureRequest.COLOR_CORRECTION_TRANSFORM,new ColorSpaceTransform(rr));}
        int ois=s.getBoolean("ois")?1:0;int[] supported=sensor.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);if(supported!=null&&Arrays.stream(supported).noneMatch(v->v==ois))throw new IllegalArgumentException("不支持所选 OIS 模式");put(b,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,ois);
        selected(b,s,"noiseReduction",CaptureRequest.NOISE_REDUCTION_MODE);selected(b,s,"edge",CaptureRequest.EDGE_MODE);selected(b,s,"hotPixel",CaptureRequest.HOT_PIXEL_MODE);selected(b,s,"shading",CaptureRequest.SHADING_MODE);selected(b,s,"aberration",CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE);selected(b,s,"distortion",CaptureRequest.DISTORTION_CORRECTION_MODE);
        applyAdvanced(b,new JSONObject(s.getString("session")),true);applyHdr(b,profile(s));applyAdvanced(b,new JSONObject(s.getString("advanced")),false);
        put(b,CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);put(b,CaptureRequest.SENSOR_SENSITIVITY,50);
        if(requestKeys.containsKey(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST.getName()))put(b,CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,100);
        return b;
    }
    private float[] floats(String s){String[] a=s.split(",");float[] f=new float[a.length];for(int i=0;i<a.length;i++)f[i]=Float.parseFloat(a[i].trim());return f;}
    private void selected(CaptureRequest.Builder b,JSONObject s,String name,CaptureRequest.Key<Integer> key)throws Exception{String v=s.getString(name);if(!v.equals("默认"))put(b,key,Integer.parseInt(v));}
    @SuppressWarnings({"rawtypes","unchecked"}) private void applyAdvanced(CaptureRequest.Builder b,JSONObject extra,boolean sessionOnly)throws Exception{
        Set<String> sessionNames=new HashSet<>();List<CaptureRequest.Key<?>> sk=logical.getAvailableSessionKeys();if(sk!=null)for(CaptureRequest.Key<?> k:sk)sessionNames.add(k.getName());
        for(Iterator<String> it=extra.keys();it.hasNext();){String name=it.next();CaptureRequest.Key key=requestKeys.get(name);if(key==null)throw new IllegalArgumentException("设备未公开可写键："+name);if(sessionOnly&&!sessionNames.contains(name))throw new IllegalArgumentException("不是会话键："+name);
            String low=name.toLowerCase(Locale.ROOT);if(low.contains("zoom")||low.contains("crop")||low.contains("cameraid")||low.contains("physicalid")||low.contains("focallength")||low.contains("switchcamera"))throw new IllegalArgumentException("主摄固定实验禁止修改镜头/变焦/裁剪："+name);
            if(low.contains("sensitivity")||low.contains("isovalue")||low.contains("gain")&&!low.equals("android.colorcorrection.gains")||low.equals("android.control.aemode")||low.equals("android.control.mode"))throw new IllegalArgumentException("最低 ISO 固定实验禁止覆盖 ISO / 增益 / 自动曝光："+name);
            JSONObject spec=extra.getJSONObject(name);Object v=typed(spec.getString("type"),spec.get("value"));b.set(key,v);if(!sessionOnly&&physicalId!=null&&physicalKeys.contains(name))b.setPhysicalCameraKey(key,v,physicalId);
        }
    }
    private Object typed(String type,Object v)throws Exception{
        if(type.equals("TonemapCurve")){JSONObject obj=(JSONObject)v;return new TonemapCurve(jsonFloats(obj.getJSONArray("red")),jsonFloats(obj.getJSONArray("green")),jsonFloats(obj.getJSONArray("blue")));}
        if(type.equals("Location")){JSONObject obj=(JSONObject)v;android.location.Location loc=new android.location.Location("manual");loc.setLatitude(obj.getDouble("latitude"));loc.setLongitude(obj.getDouble("longitude"));if(obj.has("altitude"))loc.setAltitude(obj.getDouble("altitude"));loc.setTime(obj.optLong("time",System.currentTimeMillis()));return loc;}
        String s=String.valueOf(v);switch(type){case "int":return Integer.valueOf(s);case "long":return Long.valueOf(s);case "float":return Float.valueOf(s);case "double":return Double.valueOf(s);case "byte":return Byte.valueOf(s);case "boolean":if(!s.equals("true")&&!s.equals("false"))throw new IllegalArgumentException("boolean 只能是 true/false");return Boolean.valueOf(s);case "Rational":return Rational.parseRational(s);}
        JSONArray a=(JSONArray)v;int n=a.length();switch(type){
            case "int[]":{int[] r=new int[n];for(int i=0;i<n;i++)r[i]=a.getInt(i);return r;}
            case "long[]":{long[] r=new long[n];for(int i=0;i<n;i++)r[i]=a.getLong(i);return r;}
            case "float[]":{float[] r=new float[n];for(int i=0;i<n;i++)r[i]=(float)a.getDouble(i);return r;}
            case "double[]":{double[] r=new double[n];for(int i=0;i<n;i++)r[i]=a.getDouble(i);return r;}
            case "byte[]":{byte[] r=new byte[n];for(int i=0;i<n;i++){int b=a.getInt(i);if(b<0||b>255)throw new IllegalArgumentException("byte 数组须为 0–255");r[i]=(byte)b;}return r;}
            case "Rational[]":{Rational[] r=new Rational[n];for(int i=0;i<n;i++)r[i]=Rational.parseRational(a.getString(i));return r;}
            case "Rect":return new Rect(a.getInt(0),a.getInt(1),a.getInt(2),a.getInt(3));
            case "Size":return new Size(a.getInt(0),a.getInt(1));
            case "Range<Integer>":return new Range<Integer>(a.getInt(0),a.getInt(1));
            case "RggbChannelVector":return new RggbChannelVector((float)a.getDouble(0),(float)a.getDouble(1),(float)a.getDouble(2),(float)a.getDouble(3));
            case "ColorSpaceTransform":{if(n!=9)throw new IllegalArgumentException("矩阵需 9 个有理数字符串");Rational[] r=new Rational[n];for(int i=0;i<n;i++)r[i]=Rational.parseRational(a.getString(i));return new ColorSpaceTransform(r);}
            case "MeteringRectangle[]":{MeteringRectangle[] r=new MeteringRectangle[n];for(int i=0;i<n;i++){JSONArray p=a.getJSONArray(i);r[i]=new MeteringRectangle(p.getInt(0),p.getInt(1),p.getInt(2),p.getInt(3),p.getInt(4));}return r;}
            default:throw new IllegalArgumentException("暂不支持的参数类型："+type);
        }
    }
    private float[] jsonFloats(JSONArray a)throws Exception{float[] f=new float[a.length()];for(int i=0;i<f.length;i++)f[i]=(float)a.getDouble(i);return f;}

    private final CameraCaptureSession.CaptureCallback live=new CameraCaptureSession.CaptureCallback(){public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){if(s!=session||busy||System.currentTimeMillis()-lastLive<2000)return;lastLive=System.currentTimeMillis();CaptureResult actual=physicalResult(result);message("预览实际：ISO "+actual.get(CaptureResult.SENSOR_SENSITIVITY)+" · 曝光 "+actual.get(CaptureResult.SENSOR_EXPOSURE_TIME)+" ns\n主摄固定 "+(physicalId==null?logicalId:physicalId)+" · LOFIC / DCG 硬件未验证");}};
    private CaptureResult physicalResult(TotalCaptureResult r){if(physicalId!=null){CaptureResult p=r.getPhysicalCameraResults().get(physicalId);if(p!=null)return p;}return r;}
    private void startCapture(){try{
        if(session==null||busy||!hdrSessionAccepted)return;JSONObject current=readSettings();if(!current.getString("size").equals(settings.getString("size"))||!current.getString("session").equals(settings.getString("session"))||!current.getString("hdrProfileId").equals(settings.getString("hdrProfileId")))throw new IllegalArgumentException("传感器模式、尺寸或会话参数变更后请先应用参数");
        batchSettings=current;remaining=Integer.parseInt(current.getString("frames"));sequence=0;brackets=new JSONArray();String br=current.getString("bracketEV");if(!br.isEmpty()){if(current.getBoolean("autoExposure"))throw new IllegalArgumentException("曝光包围需先关闭自动曝光");for(String x:br.split(","))brackets.put(Double.parseDouble(x.trim()));}
        for(int i=0;i<remaining;i++)makeRequest(current,true,brackets.length()==0?0:brackets.getDouble(i%brackets.length()));
        long space=new StatFs(getExternalFilesDir(null).getAbsolutePath()).getAvailableBytes();long perFrame=(long)rawReader.getWidth()*rawReader.getHeight()*2*(current.getBoolean("saveRaw16")?2:1);if(space<perFrame*remaining+100*1024*1024L)throw new IllegalStateException("可用空间不足");
        busy=true;capture.setEnabled(false);batch=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());getPreferences(0).edit().putString("settings",current.toString()).apply();worker.post(()->nextCapture());
    }catch(Exception e){remaining=0;error(e);}}
    private void nextCapture(){try{
        if(!busy||session==null)return;if(remaining<=0){finishBatch();return;}
        sequence++;double ev=brackets.length()==0?0:brackets.getDouble((sequence-1)%brackets.length());CaptureRequest req=makeRequest(batchSettings,true,ev).build();
        message("正在拍摄第 "+sequence+" 帧，剩余 "+remaining+"。请保持场景稳定。");
        captureTimeout=()->fail("等待 RAW/元数据超时；已停止本批次。");worker.postDelayed(captureTimeout,30000);
        final String batchToken=batch;final int sessionToken=generation;
        session.capture(req,new CameraCaptureSession.CaptureCallback(){
            public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){if(!busy||sessionToken!=generation||!batchToken.equals(batch))return;Long ts=physicalResult(result).get(CaptureResult.SENSOR_TIMESTAMP);if(ts==null){fail("缺失传感器时间戳");return;}results.put(ts,result);pair();}
            public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f){if(sessionToken==generation&&batchToken.equals(batch))fail("RAW 捕获失败，原因 "+f.getReason());}
        },worker);
    }catch(Exception e){fail(e.toString());}}
    private void pair(){for(Long ts:new ArrayList<Long>(images.keySet())){TotalCaptureResult r=results.remove(ts);if(r==null)continue;Image im=images.remove(ts);worker.removeCallbacks(captureTimeout);try{saveFrame(im,r);remaining--;if(remaining>0)worker.postDelayed(()->nextCapture(),Math.round(number(batchSettings,"intervalMs")));else finishBatch();}catch(Exception e){fail(e.toString());}finally{im.close();}}}
    private void finishBatch(){busy=false;remaining=0;runOnUiThread(()->capture.setEnabled(session!=null));message("完成。已保存 "+sequence+" 帧至 下载/JCCamera。DNG 位深须以白电平和样本检查为准。");}
    private void fail(String reason){busy=false;remaining=0;if(captureTimeout!=null)worker.removeCallbacks(captureTimeout);for(Image i:images.values())i.close();images.clear();results.clear();clearPending();runOnUiThread(()->{capture.setEnabled(session!=null);open.setEnabled(true);});message(reason);}
    private void clearPending(){for(Uri u:new ArrayList<Uri>(pendingUris))try{getContentResolver().delete(u,null,null);}catch(Exception ignored){}pendingUris.clear();}
    private Uri createOutput(String name,String mime)throws Exception{ContentValues cv=new ContentValues();cv.put(MediaStore.MediaColumns.DISPLAY_NAME,name);cv.put(MediaStore.MediaColumns.MIME_TYPE,mime);cv.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/JCCamera");cv.put(MediaStore.MediaColumns.IS_PENDING,1);Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);if(uri==null)throw new IOException("不能建立输出");pendingUris.add(uri);return uri;}
    private void publish(Uri u){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(u,v,null,null);pendingUris.remove(u);}
    private void saveFrame(Image image,TotalCaptureResult total)throws Exception{
        if(physicalId!=null&&!total.getPhysicalCameraResults().containsKey(physicalId))throw new IllegalStateException("主摄物理帧元数据缺失，拒绝用其他镜头的标定信息写 DNG");
        if(!Integer.valueOf(50).equals(physicalResult(total).get(CaptureResult.SENSOR_SENSITIVITY)))throw new IllegalStateException("实际 ISO 不是最低 50，拒绝保存成功样张");
        CaptureResult actual=physicalResult(total);String base="JC_"+batch+String.format(Locale.US,"_%03d",sequence);
        JSONObject report=new JSONObject();report.put("channel","Camera2 RAW_SENSOR; LOFIC not verified");report.put("logicalCameraId",logicalId);report.put("pinnedPhysicalCameraId",physicalId);report.put("physicalMetadataPresent",physicalId==null||total.getPhysicalCameraResults().containsKey(physicalId));report.put("settings",batchSettings);report.put("frameIndex",sequence);report.put("bracketEV",brackets.length()==0?0:brackets.getDouble((sequence-1)%brackets.length()));report.put("timestamp",image.getTimestamp());report.put("width",image.getWidth());report.put("height",image.getHeight());report.put("containerBits",16);report.put("staticWhiteLevel",sensor.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));report.put("dynamicWhiteLevel",actual.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL));report.put("blackLevelPattern",value(sensor.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)));report.put("actual",captureJson(actual));report.put("logicalActual",captureJson(total));
        report.put("submittedRequest",requestJson(total.getRequest()));JSONObject hdr=hdrReport(batchSettings);
        hdr.put("submittedAeMode",value(total.getRequest().get(CaptureRequest.CONTROL_AE_MODE)));
        hdr.put("manualExposureMayDowngradeLofic",Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF).equals(total.getRequest().get(CaptureRequest.CONTROL_AE_MODE)));report.put("hdrExperiment",hdr);
        report.put("moduleReference",moduleReference());
        Image.Plane plane=image.getPlanes()[0];report.put("rowStride",plane.getRowStride());report.put("pixelStride",plane.getPixelStride());
        if(plane.getPixelStride()<2)throw new IllegalStateException("RAW_SENSOR 缓冲区不是预期的 16 位样本布局");
        ByteBuffer buffer=plane.getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);int min=65535,max=0;long nonzero=0;int[] lowBits=new int[16];
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int val=buffer.getShort(y*plane.getRowStride()+x*plane.getPixelStride())&65535;min=Math.min(min,val);max=Math.max(max,val);if(val!=0)nonzero++;lowBits[val&15]++;}
        report.put("sampleMin",min);report.put("sampleMax",max);report.put("nonzeroSamples",nonzero);report.put("lowFourBitsHistogram",value(lowBits));
        Uri dng=createOutput(base+".dng","image/x-adobe-dng");
        try(DngCreator creator=new DngCreator(sensor,actual);OutputStream out=getContentResolver().openOutputStream(dng)){creator.setDescription("JC Camera2 main sensor RAW. LOFIC NOT VERIFIED. Sidecar contains requested/actual settings.");Integer orientation=sensor.get(CameraCharacteristics.SENSOR_ORIENTATION);creator.setOrientation(orientation!=null&&orientation==90?6:1);creator.writeImage(out,image);}
        Uri raw=null;if(batchSettings.getBoolean("saveRaw16")){raw=createOutput(base+".raw16","application/octet-stream");try(OutputStream out=getContentResolver().openOutputStream(raw)){byte[] row=new byte[image.getWidth()*2];for(int y=0;y<image.getHeight();y++){for(int x=0;x<image.getWidth();x++){int at=y*plane.getRowStride()+x*plane.getPixelStride();row[2*x]=buffer.get(at);row[2*x+1]=buffer.get(at+1);}out.write(row);}}}
        Uri meta=createOutput(base+".json","application/json");try(OutputStream out=getContentResolver().openOutputStream(meta)){out.write(report.toString(2).getBytes("UTF-8"));}
        publish(dng);if(raw!=null)publish(raw);publish(meta);
    }
    private JSONObject captureJson(CaptureResult r)throws Exception{JSONObject j=new JSONObject();for(CaptureResult.Key<?> k:r.getKeys()){try{j.put(k.getName(),value(r.get(k)));}catch(Exception e){j.put(k.getName(),"unreadable: "+e);}}return j;}
    private static Object value(Object v){if(v==null)return JSONObject.NULL;if(v.getClass().isArray()){JSONArray a=new JSONArray();for(int i=0;i<Array.getLength(v);i++)a.put(value(Array.get(v,i)));return a;}if(v instanceof Rational)return v.toString();if(v instanceof Double&&!Double.isFinite((Double)v))return v.toString();if(v instanceof Float&&!Float.isFinite((Float)v))return v.toString();if(v instanceof Number||v instanceof Boolean||v instanceof String)return v;return v.toString();}
    private JSONObject capabilities()throws Exception{JSONObject j=new JSONObject();j.put("logicalId",logicalId);j.put("physicalId",physicalId);j.put("rootRequired",false);j.put("loficVerified",false);JSONObject c=new JSONObject();for(CameraCharacteristics.Key<?> k:sensor.getKeys())try{c.put(k.getName(),value(sensor.get(k)));}catch(Exception ignored){}j.put("mainSensorCharacteristics",c);JSONArray req=new JSONArray();for(String k:requestKeys.keySet())req.put(k);j.put("writableRequestKeys",req);j.put("physicalRequestKeys",new JSONArray(physicalKeys));JSONArray ss=new JSONArray();List<CaptureRequest.Key<?>> keys=logical.getAvailableSessionKeys();if(keys!=null)for(CaptureRequest.Key<?> k:keys)ss.put(k.getName());j.put("sessionKeys",ss);JSONArray modes=new JSONArray();for(HdrMode h:hdrModes)modes.put(modeJson(h));j.put("hdrProfiles",modes);j.put("mainPhysicalDcgCapabilities",value(mainHdrCaps));j.put("sessionConfigured",hdrSessionAccepted);j.put("lastSubmittedSessionParameters",submittedSession);j.put("dcgHardwareVerified",false);j.put("moduleReference",moduleReference());return j;}
    private void showCapabilities(){try{String text=capabilities().toString(2);TextView t=new TextView(this);t.setText(text);t.setTextSize(12);t.setTextIsSelectable(true);t.setPadding(dp(12),dp(12),dp(12),dp(12));ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle("主摄能力 / 参数键").setView(s).setNegativeButton("关闭",null).setPositiveButton("导出 JSON",(d,w)->{try{Uri uri=createOutput("JC_capabilities_"+System.currentTimeMillis()+".json","application/json");try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(text.getBytes("UTF-8"));}publish(uri);message("能力清单已导出到 下载/JCCamera。");}catch(Exception e){error(e);}}).show();}catch(Exception e){error(e);}}
    private void closeCamera(){generation++;remaining=0;if(capture!=null)capture.setEnabled(false);if(open!=null)open.setEnabled(true);worker.post(()->closeOnWorker());}
    private void closeOnWorker(){busy=false;hdrSessionAccepted=false;if(captureTimeout!=null)worker.removeCallbacks(captureTimeout);if(session!=null){session.close();session=null;}if(device!=null){device.close();device=null;}if(rawReader!=null){rawReader.close();rawReader=null;}if(previewSurface!=null){previewSurface.release();previewSurface=null;}for(Image i:images.values())try{i.close();}catch(Exception ignored){}images.clear();results.clear();opening=false;clearPending();}
    @Override protected void onResume(){super.onResume();resumed=true;}
    @Override protected void onPause(){resumed=false;closeCamera();super.onPause();}
    @Override protected void onDestroy(){closeCamera();worker.post(()->thread.quitSafely());super.onDestroy();}
}
