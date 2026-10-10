package local.jc.mainraw;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Size;
import android.util.SizeF;
import android.util.Range;
import android.util.SparseArray;
import android.util.Log;
import android.view.View;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import android.view.WindowManager;
import android.widget.*;
import dalvik.system.DexClassLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent-device hybrid capture experiment.
 *
 * The CameraDevice is kept open while LOFIC/50MP sessions are swapped.  This
 * follows MotionCam's acquisition architecture without copying its GPL code.
 * A mode is reported as usable only after the output buffer and CaptureResult
 * have been written; a request or a configured session alone is not evidence.
 */
public final class FastHybridActivity extends Activity {
    private static final String TAG = "JCFastHybrid";
    private static final String MODE_KEY = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    private static final int PHOTO_50MP_OPERATION_MODE = 0x900d;
    private static final Size PHOTO_PREVIEW_SIZE = new Size(1440, 1080);
    private static final Size PHOTO_DOWNSCALE_SIZE = new Size(640, 480);
    private static final Size LOFIC_RAW_SIZE = new Size(4096, 3072);
    // Photo's MCTFE RAW5 port55 is a padded 8192-column buffer.  The vendor
    // scaler table also registers 8192x6144 for the 50MP path; requesting the
    // cropped 8160 width makes CameraService reject the surface before CHI.
    // Keep the actual Image width/stride in the journal for verification.
    private static final Size PHOTO_RAW_SIZE = new Size(8192, 6144);
    private static final long PHOTO_DOWNSCALE_USE_CASE = 524555L;
    private static final String SESSION_CLIENT_NAME = "com.xiaomi.sessionparams.clientName";
    private static final String SESSION_PROCESS_ID = "com.xiaomi.sessionparams.processId";
    private static final String SESSION_OPERATION = "com.xiaomi.sessionparams.operation";
    private static final String SESSION_STREAM_USECASE = "com.xiaomi.sessionparams.MiStreamUsecase";
    private static final String SESSION_FUNCTION_MASK = "com.xiaomi.sessionparams.customizeFunctionMask";
    private static final String SESSION_CLOUD_SWITCH = "com.xiaomi.sessionparams.trdCloudSwitch";
    private static final String OUT_DIR = "HybridFast";
    // Photo's ManualRaw table contains RAW_SENSOR (32), RAW10 (37), and a
    // vendor RAW variant (36).  The public Camera2 map normally exposes only
    // RAW_SENSOR, so keep the requested format explicit for controlled probes.
    private static final int DEFAULT_MP50_FORMAT = ImageFormat.RAW_SENSOR;
    private static final int[] MP50_FORMATS = new int[]{ImageFormat.RAW_SENSOR, 36, ImageFormat.RAW10};
    private final HandlerThread thread = new HandlerThread("JCFastHybrid-Capture");
    private Handler worker;
    private CameraManager manager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CameraCharacteristics sensor;
    private String logicalId, physicalId;
    private ImageReader rawReader;
    private ImageReader bridgeMp50Reader;
    private final Set<Long> bridgeRaw10 = new HashSet<>(), bridgeRaw14 = new HashSet<>();
    private long bridgeAnchorTimestamp = -1;
    private boolean bridgeSent;
    private boolean reverseFirstSent, reverseHdrSent, pairFinalized;
    private boolean single50Finalized,single50ShutterIssued;
    private boolean classicExposureFinished;
    private java.util.concurrent.FutureTask<String> classicFileAudit;
    private boolean classicHdr(){return getIntent().getBooleanExtra("classicHdr",false);}
    private void startClassicFileAudit(){
        if(!classicHdr())return;
        classicFileAudit=new java.util.concurrent.FutureTask<>(()->{
            long begin=SystemClock.elapsedRealtime();
            if(getIntent().hasExtra("classicDriverProofSha")){
                File proof=new File(root,"driver-file-proof.txt");String expected=getIntent().getStringExtra("classicDriverProofSha");
                if(expected==null||!expected.matches("[0-9a-f]{64}")||!Native50Store.hash(proof).equals(expected))throw new IOException("driver-generation proof changed");
                String text;try(InputStream in=new FileInputStream(proof)){text=new String(NativeRaw.bytes(in,16384),java.nio.charset.StandardCharsets.UTF_8);}
                final long elapsed=SystemClock.elapsedRealtime()-begin;worker.post(()->record("classic_file_audit_elapsed",json("elapsedMs",elapsed,"hashSource","verified_unchanged_provider_generation","proofSha256",expected)));return text;
            }
            String provider=getIntent().getStringExtra("focusProviderPid");if(provider==null||!provider.matches("[0-9]+"))throw new IOException("invalid file audit provider");
            String cmd="CLASSPATH="+RootProcess.quote(getApplicationInfo().sourceDir)+" timeout 10 app_process /system/bin local.jc.mainraw.PairFileAuditMain";
            java.lang.Process p=RootProcess.start(cmd,true);
            java.util.concurrent.FutureTask<String> read=new java.util.concurrent.FutureTask<>(()->{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=p.getInputStream().read(b))>=0){if(out.size()+n>4*1024*1024)throw new IOException("file audit oversized");out.write(b,0,n);}return out.toString("UTF-8");});
            Thread drain=new Thread(read,"JC-Classic-File-Read");drain.setDaemon(true);drain.start();
            try{if(!p.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)||p.exitValue()!=0)throw new IOException("file audit failed");
                String evidence=read.get(1,java.util.concurrent.TimeUnit.SECONDS);
                final long elapsed=SystemClock.elapsedRealtime()-begin;worker.post(()->record("classic_file_audit_elapsed",json("elapsedMs",elapsed,"hashSource","fresh_root_java_SHA256_read_from_mounted_paths")));return evidence;
            }finally{p.destroy();}
        });Thread t=new Thread(classicFileAudit,"JC-Classic-File-Audit");t.setDaemon(true);t.start();
    }
    private void classicExposureDone()throws Exception{
        if(!classicHdr()||classicExposureFinished||bridgeAnchorTimestamp<=0||!bridgeRaw14.contains(bridgeAnchorTimestamp)||!bridgeRaw10.contains(bridgeAnchorTimestamp))return;
        classicExposureFinished=true;
        record("classic_exposure_finished",json("timestampNs",bridgeAnchorTimestamp,"authority","matching capture result and both copied RAW buffers"));
        runOnUiThread(()->{status.setText("曝光已结束，可以移动手机 · 正在检查与保存");status.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);});
    }
    private boolean single50(){return getIntent().hasExtra("native50Id");}
    private void finishSingle50(){
        if(!single50()||single50Finalized)return;
        try{
            JSONObject result=Native50Store.validate(root,events);if(result==null)return;
            single50Finalized=true;closeCamera();
            result.put("dng",Native50Dng.export(this,root,result,"qbayer".equals(getIntent().getStringExtra("native50Layout"))));
            PairCaptureStore.atomic(new File(root,"complete.json"),result);runOnUiThread(this::finish);
        }catch(Exception e){single50Finalized=true;closeCamera();try{PairCaptureStore.atomic(new File(root,"failed.json"),json("error",e.toString()));}catch(Exception ignored){}say("拍摄未通过检查");}
    }
    private JSONObject reverseMp50Result;
    private long reverseMp50Pixels = -1;
    private JSONObject tripodQuick;
    private final Map<Long,int[][]> tripodHistograms=new HashMap<>();
    private boolean tripodSlowSent;
    private long pairHdrExposure=PairExposurePolicy.SHUTTER_NS;
    private boolean manualPair(){return reverseBridge() && "manual".equals(getIntent().getStringExtra("samplingMode"));}
    private boolean flexiblePair(){return tripodPair()||manualPair();}
    private boolean tripodPair(){return reverseBridge() && "tripod".equals(getIntent().getStringExtra("samplingMode"));}
    private void applyPairHdr(long exposure)throws Exception {
        pairHdrExposure=exposure;
        if(tripodSlowSent){
            // The quick frame already verified mode and unity gains. Change only exposure.
            bridgeProperties("setprop vendor.debug.camera.miaec.shutter_long "+exposure
                    +"; setprop vendor.debug.camera.miaec.shutter_mid "+exposure
                    +"; setprop vendor.debug.camera.miaec.shutter_short "+exposure);
            return;
        }
        bridgeProperties("setprop persist.vendor.sat.forceModeSele 1; setprop persist.vendor.sat.binningModeW 11; "
                +"setprop vendor.debug.camera.miaec.auto_hdr_mode 8; "
                +"setprop vendor.debug.camera.miaec.shutter_long "+exposure+"; setprop vendor.debug.camera.miaec.shutter_mid "+exposure
                +"; setprop vendor.debug.camera.miaec.shutter_short "+exposure
                +"; setprop vendor.debug.camera.miaec.gain_long 1; setprop vendor.debug.camera.miaec.gain_mid 1; setprop vendor.debug.camera.miaec.gain_short 1");
    }
    private void tripodAdvance(long generation)throws Exception {
        if(!tripodPair()||tripodSlowSent||tripodQuick==null)return;
        long ts=tripodQuick.getLong("timestampNs");
        if(!bridgeRaw14.contains(ts)||!bridgeRaw10.contains(ts)||!tripodHistograms.containsKey(ts))return;
        if(!isActive(generation,session))throw new IOException("Tripod session expired");
        long actual=tripodQuick.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0);
        NativeEttr.Result plan=NativeEttr.fromChannels(tripodHistograms.remove(ts),actual);
        // Re-requesting the integer-reported first exposure loses a hardware line.
        plan.exposureNs=Math.max(NativeEttr.FIRST_NS,plan.exposureNs);
        tripodSlowSent=true;
        record("tripod_ettr_plan",json("quickTimestampNs",ts,"quickExposureNs",actual,"top10Mean",plan.mean,
                "rule",NativeEttr.CHANNEL_RULE,"channelTop10Means",JSONObject.wrap(plan.channelMeans),
                "selectedChannel",NativeEttr.CHANNELS[plan.selectedChannel],"slowExposureNs",plan.exposureNs,"limited",plan.limited,"computedElapsedNs",SystemClock.elapsedRealtimeNanos()));
        applyPairHdr(plan.exposureNs);
        bridgeAcquireAnchor(generation);
    }
    private boolean reverseBridge() { return bridgeProbe() && getIntent().getBooleanExtra("mp50HdrBridge", false); }
    private void bridgeProperties(String command) throws Exception {
        java.lang.Process p = RootProcess.start(command, true);
        java.util.concurrent.FutureTask<Integer> done = new java.util.concurrent.FutureTask<>(() -> p.waitFor());
        Thread t = new Thread(done, "JC-Pair-Properties"); t.setDaemon(true); t.start();
        try {
            if (done.get(2, java.util.concurrent.TimeUnit.SECONDS) != 0) throw new IOException("pair property transition failed");
        } finally { p.destroy(); }
    }
    private void reverseBegin(long generation) throws Exception {
        if (reverseFirstSent) throw new IOException("first shutter already issued");
        reverseFirstSent = true;
        bridgePreparedSnapshot = buildNativeSnapshot(Mode.MP50);
        long pairExposure = getIntent().getLongExtra("pairExposureNs", 1_000_000L);
        double pairGain = Double.parseDouble(getIntent().hasExtra("pairGain") ? getIntent().getStringExtra("pairGain") : "1.0");
        if (pairExposure <= 0 || pairExposure > (flexiblePair()?NativeEttr.MAX_NS:PairExposurePolicy.SHUTTER_NS) || !Double.isFinite(pairGain) || pairGain < 1 || pairGain > 16 || (flexiblePair() && pairGain!=1))
            throw new IOException("invalid metered exposure plan");
        bridgeProperties(FastHybridBridgeTransition.APPLY + "; setprop vendor.debug.camera.miaec.shutter_long " + pairExposure
                + "; setprop vendor.debug.camera.miaec.shutter_mid " + pairExposure + "; setprop vendor.debug.camera.miaec.shutter_short " + pairExposure
                + "; setprop vendor.debug.camera.miaec.gain_long " + pairGain + "; setprop vendor.debug.camera.miaec.gain_mid " + pairGain
                + "; setprop vendor.debug.camera.miaec.gain_short " + pairGain);
        record("reverse_mp50_begin", json("generation", generation));
        nativeSnapshot(session, Mode.MP50, PHOTO_RAW_SIZE, generation);
    }
    private void reverseTryHdr(long generation) throws Exception {
        if (!reverseBridge() || bridgeSent || reverseHdrSent || reverseMp50Result == null
                || reverseMp50Pixels != reverseMp50Result.optLong("timestampNs", -2)) return;
        JSONObject m = reverseMp50Result;
        long exposure = m.optLong("exposureNs", -1);
        if (!isActive(generation, session) || m.optInt("actualSensorMode", -1) != 0
                || !m.optBoolean("physicalResultPresent") || !m.optBoolean("focusVerified",m.optBoolean("factoryInfinityVerified"))
                || !(flexiblePair()?PairExposurePolicy.tripod50(exposure,m.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0)):PairExposurePolicy.safe50(exposure,m.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0)))) throw new IOException("50MP safety shutter or mode rejected; no HDR shutter");
        reverseHdrSent = true;
        long hdrExposure=manualPair()?getIntent().getLongExtra("pairHdrExposureNs",-1):tripodPair()?NativeEttr.FIRST_NS:PairExposurePolicy.SHUTTER_NS;
        if(hdrExposure<100000||hdrExposure>NativeEttr.MAX_NS)throw new IOException("Invalid manual HDR shutter");
        applyPairHdr(hdrExposure);
        bridgeAcquireAnchor(generation);
    }
    private CaptureRequest bridgePreparedSnapshot;
    private FastHybridBridgeTransition bridgePreparedTransition;
    private boolean bridgeFast() { return bridgeProbe() && getIntent().getBooleanExtra("bridgeFast", false); }
    private boolean bridgeProbe() { return getIntent().getBooleanExtra("hdrMp50Bridge", false); }

    private SurfaceTexture previewTexture;
    private Surface previewSurface;
    private SurfaceTexture downscaleTexture;
    private Surface downscaleSurface;
    private final ArrayList<ImageReader> readers = new ArrayList<>();
    private final Object stateLock = new Object();
    private volatile boolean destroyed;
    private int sequence;
    private final JSONArray events = new JSONArray();
    // Camera callbacks must never wait on flash/storage.  MotionCam keeps the
    // camera buffer in a bounded native queue and lets a consumer persist it;
    // this Java prototype mirrors that ownership boundary with one writer.
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "JCFastHybrid-Writer"); t.setPriority(Thread.NORM_PRIORITY); return t; });
    private final ExecutorService inspector = Executors.newSingleThreadExecutor();
    private final ArrayList<PendingFrame> batchFrames = new ArrayList<>();
    private boolean chainAborted, pairReported;
    private long focusWindowStartMs;
    private volatile JSONObject focusEvidence;
    private PendingFrame pendingFrame;
    private static final class PendingFrame {
        final int sequence, chainIndex;
        final long generation;
        final Mode mode;
        final Size size;
        final Mode[] chain;
        long startedTimestamp;
        JSONObject result, ready, saved;
        final FastHybridFrameLifecycle lifecycle = new FastHybridFrameLifecycle();
        boolean imageReceived, terminal, ioPending;
        PendingFrame(int sequence, long generation, Mode mode, Size size, int chainIndex, Mode[] chain) {
            this.sequence = sequence; this.generation = generation; this.mode = mode;
            this.size = size; this.chainIndex = chainIndex; this.chain = chain;
        }
    }
    private Mode configuredMode;
    private Size configuredSize;
    private int configuredFormat = -1;
    private TextView status;
    private File root;
    private boolean autoLofic;
    private boolean autoMp50;
    private boolean autoHybrid;
    private boolean probeMode0;
    private boolean probePhotoBridge;
    private boolean unsafePhotoBridge;
    private boolean armLofic;
    private boolean pendingAutoShoot;
    private boolean opening;
    private boolean configuring;
    private long nextSessionGeneration;
    private long activeSessionGeneration;
    private long retryUntil;
    private String requestedCameraId;
    private String requestedPhysicalId;
    private boolean skipPhysicalStreamId;
    private Integer sessionOperation = 0;
    private int requestedMp50Format = DEFAULT_MP50_FORMAT;
    private long requestedMp50Usage = 3L;
    private int requestedMp50SessionType = PHOTO_50MP_OPERATION_MODE;
    private boolean requestedMp50AuxStreams = true;
    private boolean requestedMp50YuvAux;
    private boolean requestedMp50GpuAux;
    private boolean requestedSensorHint;

    private enum Mode { LOFIC, MP50 }
    /** Load Photo's implementation before the same-name framework stubs. */
    private static final class PhotoDexClassLoader extends DexClassLoader {
        PhotoDexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath) {
            super(dexPath, optimizedDirectory, librarySearchPath, FastHybridActivity.class.getClassLoader());
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.android.camera.") || name.equals("com.android.camera")
                    || name.startsWith("com.xiaomi.")
                    || name.startsWith("v6.")) {
                try { return findClass(name); } catch (ClassNotFoundException ignored) { }
            }
            return super.loadClass(name, resolve);
        }
    }
    private static final class OutputSpec {
        final Size size; final int format;
        OutputSpec(Size size, int format) { this.size = size; this.format = format; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), OUT_DIR);
        String pairId = getIntent().getStringExtra("pairSessionId");
        if (pairId != null) {
            if (!pairId.matches("p[0-9]{13}")) throw new IllegalArgumentException("Invalid pair session");
            root = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Pairs/" + pairId);
        }
        if(single50()){
            String id=getIntent().getStringExtra("native50Id"),layout=getIntent().getStringExtra("native50Layout");
            if(!id.matches("n[0-9]{13}")||!("official".equals(layout)||"qbayer".equals(layout)))throw new IllegalArgumentException("Invalid native50 session");
            root=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"Native50/"+id+"/"+layout);
        }
        root.mkdirs();
        autoLofic = getIntent().getBooleanExtra("autoLofic", false);
        autoMp50 = getIntent().getBooleanExtra("autoMp50", false);
        autoHybrid = getIntent().getBooleanExtra("autoHybrid", false);
        probeMode0 = getIntent().getBooleanExtra("probeMode0", false);
        probePhotoBridge = getIntent().getBooleanExtra("probePhotoBridge", false);
        unsafePhotoBridge = getIntent().getBooleanExtra("unsafePhotoBridge", false);
        armLofic = getIntent().getBooleanExtra("armLofic", false);
        requestedCameraId = getIntent().getStringExtra("cameraId");
        requestedPhysicalId = getIntent().getStringExtra("physicalId");
        skipPhysicalStreamId = getIntent().getBooleanExtra("skipPhysicalStreamId", false);
        if (getIntent().hasExtra("operation")) sessionOperation = getIntent().getIntExtra("operation", 0);
        requestedMp50Format = readMp50Format(getIntent().getIntExtra("rawFormat", DEFAULT_MP50_FORMAT));
        requestedMp50Usage = getIntent().getLongExtra("rawUsage", 3L);
        requestedMp50SessionType = getIntent().getIntExtra("mp50SessionType", PHOTO_50MP_OPERATION_MODE);
        requestedMp50AuxStreams = getIntent().getBooleanExtra("mp50AuxStreams", true);
        requestedMp50YuvAux = getIntent().getBooleanExtra("mp50YuvAux", false);
        requestedMp50GpuAux = getIntent().getBooleanExtra("mp50GpuAux", false);
        requestedSensorHint = getIntent().getBooleanExtra("sensorHint", false);
        thread.start(); worker = new Handler(thread.getLooper());
        manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);

        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this); title.setText("JC 快速 Hybrid 采集实验\n长驻 CameraDevice · ISO 50 · 无限远"); title.setTextSize(20); box.addView(title);
        status = new TextView(this); status.setText("尚未打开相机。输出：Android/data/.../files/Download/HybridFast"); status.setTextIsSelectable(true); box.addView(status);
        add(box, "打开并预热持久会话", v -> post(this::openCamera));
        add(box, "单张 LOFIC（4096×3072）", v -> post(() -> captureMode(Mode.LOFIC)));
        add(box, "50MP 诊断（原生输出尚未验收）", v -> post(() -> captureMode(Mode.MP50)));
        add(box, "探测 50MP RAW10（格式 37）", v -> post(() -> { requestedMp50Format = ImageFormat.RAW10; captureMode(Mode.MP50); }));
        add(box, "探测 50MP 厂商 RAW（格式 36）", v -> post(() -> { requestedMp50Format = 36; captureMode(Mode.MP50); }));
        add(box, "探测 Photo 私有 CHI 输出桥", v -> post(this::probePhotoPrivateBridge));
        add(box, "LOFIC → 50MP → LOFIC", v -> post(() -> sequenceModes(new Mode[]{Mode.LOFIC, Mode.MP50, Mode.LOFIC})));
        add(box, "50MP → LOFIC", v -> post(() -> sequenceModes(hybridModes())));
        add(box, "停止并关闭", v -> post(this::closeCamera));
        if (getIntent().hasExtra("pairSessionId")||single50()) {
            box.removeAllViews();box.setPadding(0,0,0,0);box.setBackgroundColor(0xff000000);
            TextView heading=new TextView(this);heading.setText(single50()?"JC · 50MP 双 RAW":classicHdr()?"JC · RAW10 + RAW14":"JC · 50MP + HDR");heading.setTextSize(20);heading.setTextColor(0xffffffff);heading.setGravity(android.view.Gravity.CENTER);
            box.addView(heading,new LinearLayout.LayoutParams(-1,Math.round(52*getResources().getDisplayMetrics().density)));
            if(single50()){
                TextView locked=new TextView(this);locked.setTextColor(0xffffffff);locked.setTextSize(14);locked.setGravity(android.view.Gravity.CENTER);
                locked.setText(String.format(Locale.US,"锁定 ISO 70 / 1×增益 · %.3f ms (1/%.1f s)\n焦点 %.4f · %s",getIntent().getLongExtra("sensorExposureNs",0)/1e6,1e9/getIntent().getLongExtra("sensorExposureNs",1),getIntent().getFloatExtra("pairFocusDistance",0),"qbayer".equals(getIntent().getStringExtra("native50Layout"))?"原生 QBayer":"官方普通 Bayer"));
                box.addView(locked,new LinearLayout.LayoutParams(-1,-2));
            }
            ImageView retained=new ImageView(this);retained.setScaleType(ImageView.ScaleType.FIT_CENTER);retained.setImageBitmap(PairCaptureView.lastFrame);
            box.addView(retained,new LinearLayout.LayoutParams(-1,0,1));
            status.setTextColor(0xffffffff);status.setTextSize(16);status.setGravity(android.view.Gravity.CENTER);status.setPadding(16,24,16,36);
            status.setText("正在核验 · 画面暂缓更新，请保持稳定");box.addView(status,new LinearLayout.LayoutParams(-1,-2));
        }
        setContentView(box);
        if(single50())worker.post(new Runnable(){public void run(){if(single50Finalized||destroyed)return;long left=getIntent().getLongExtra("native50NotBeforeMs",0)-SystemClock.elapsedRealtime();runOnUiThread(()->status.setText(left>0?((left+999)/1000)+" 秒后拍摄 · 曝光与焦点已锁定":"正在核验及保存 · 画面暂缓更新"));worker.postDelayed(this,200);}});
        pendingAutoShoot = autoMp50;
        if (autoLofic || autoMp50 || autoHybrid || probeMode0 || probePhotoBridge) {
            retryUntil = SystemClock.elapsedRealtime() + 30_000L;
        }
        if (autoLofic || autoMp50 || autoHybrid || probeMode0 || probePhotoBridge || armLofic) post(this::openCamera);
    }

    private void add(LinearLayout box, String text, View.OnClickListener l) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(l); box.addView(b); }
    private void post(Runnable r) { if (worker != null) worker.post(r); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        autoLofic = intent.getBooleanExtra("autoLofic", false);
        autoMp50 = intent.getBooleanExtra("autoMp50", false);
        autoHybrid = intent.getBooleanExtra("autoHybrid", false);
        probePhotoBridge = intent.getBooleanExtra("probePhotoBridge", false);
        unsafePhotoBridge = intent.getBooleanExtra("unsafePhotoBridge", false);
        armLofic = intent.getBooleanExtra("armLofic", false);
        if (intent.hasExtra("cameraId")) requestedCameraId = intent.getStringExtra("cameraId");
        if (intent.hasExtra("physicalId")) requestedPhysicalId = intent.getStringExtra("physicalId");
        if (intent.hasExtra("skipPhysicalStreamId")) skipPhysicalStreamId = intent.getBooleanExtra("skipPhysicalStreamId", false);
        if (intent.hasExtra("operation")) sessionOperation = intent.getIntExtra("operation", 0);
        if (intent.hasExtra("rawFormat")) requestedMp50Format = readMp50Format(intent.getIntExtra("rawFormat", DEFAULT_MP50_FORMAT));
        if (intent.hasExtra("rawUsage")) requestedMp50Usage = intent.getLongExtra("rawUsage", 3L);
        if (intent.hasExtra("mp50SessionType")) requestedMp50SessionType = intent.getIntExtra("mp50SessionType", PHOTO_50MP_OPERATION_MODE);
        if (intent.hasExtra("mp50AuxStreams")) requestedMp50AuxStreams = intent.getBooleanExtra("mp50AuxStreams", true);
        if (intent.hasExtra("mp50YuvAux")) requestedMp50YuvAux = intent.getBooleanExtra("mp50YuvAux", false);
        if (intent.hasExtra("mp50GpuAux")) requestedMp50GpuAux = intent.getBooleanExtra("mp50GpuAux", false);
        if (intent.hasExtra("sensorHint")) requestedSensorHint = intent.getBooleanExtra("sensorHint", false);
        if (intent.getBooleanExtra("releaseCamera", false)) {
            post(() -> { autoLofic = false; autoMp50 = false; armLofic = false; pendingAutoShoot = false; retryUntil = 0; closeCamera(); record("camera_released", "ready_for_native_photo"); });
        } else if (armLofic) {
            // Keep the process and retry the open while the native Photo
            // client owns the sensor.  No shutter is issued in arm mode.
            retryUntil = SystemClock.elapsedRealtime() + 15_000L;
            post(this::openCamera);
        } else if (autoHybrid) {
            autoLofic = false; autoMp50 = false; armLofic = false; pendingAutoShoot = false;
            retryUntil = SystemClock.elapsedRealtime() + 15_000L;
            post(() -> {
                if (camera == null) openCamera();
                else if (!opening && !configuring) { autoHybrid = false; configure(Mode.MP50, true, 0, hybridModes()); }
            });
        } else if (autoMp50) {
            autoLofic = false; armLofic = false; pendingAutoShoot = true;
            retryUntil = SystemClock.elapsedRealtime() + 15_000L;
            post(() -> { if (camera == null) openCamera(); else configure(Mode.MP50, true); });
        } else if (autoLofic) {
            armLofic = false;
            retryUntil = SystemClock.elapsedRealtime() + 15_000L;
            pendingAutoShoot = true;
            post(() -> {
                if (camera == null) openCamera();
                else if (opening || configuring) { /* onOpened/onConfigured will consume the pending shot */ }
                else if (session != null && configuredMode == Mode.LOFIC) {
                    pendingAutoShoot = false;
                    shoot(Mode.LOFIC, configuredSize, 0, null);
                } else configure(Mode.LOFIC, true);
            });
        }
    }
    private void say(String s) {
        String visible = classicHdr()&&classicExposureFinished ? "曝光已结束，可以移动手机 · 正在检查与保存" : (getIntent().hasExtra("pairSessionId")||single50()) ? (s.contains("失败") ? "拍摄未完成，正在恢复…" : "正在核验和拍摄 · 画面暂缓更新") : s;
        runOnUiThread(() -> status.setText(visible));
    }
    private boolean record(String type, Object value) {
        try {
            synchronized (events) {
                events.put(new JSONObject().put("time", System.currentTimeMillis()).put("monotonicNs", SystemClock.elapsedRealtimeNanos()).put("type", type).put("value", value));
                saveJournal();
            }
            if(single50()&&!single50Finalized&&(type.endsWith("_saved")||type.endsWith("_result")||type.equals("error")))worker.post(this::finishSingle50);
            if (getIntent().hasExtra("pairSessionId") && !pairFinalized && (type.endsWith("_saved") || type.endsWith("_result") || type.equals("error")))
                worker.post(this::finishPhonePair);
            return true;
        } catch (Exception e) { Log.e(TAG, "journal", e); return false; }
    }
    private void finishPhonePair() {
        if (pairFinalized || (!reverseBridge()&&!classicHdr())) return;
        try {
            JSONObject result = classicHdr()?ClassicHdrStore.validate(root,events):PairCaptureStore.validate(root, events);
            if (result == null) return;
            pairFinalized = true;
            closeCamera();
            if(classicHdr())result.put("dng",ClassicHdrStore.dng(this,root,result));
            PairCaptureStore.atomic(new File(root,"complete.json"), result);
            say("拍摄完成，正在保存…");
        } catch (Exception e) {
            pairFinalized = true; closeCamera();
            try { PairCaptureStore.atomic(new File(root,"failed.json"),json("error",e.toString())); } catch (Exception ignored) { }
            say("拍摄未通过检查，未加入相册");
        }
    }

    private void saveJournal() throws Exception {
        File temp = new File(root, "journal.json.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write((bridgeFast() ? events.toString() : events.toString(2)).getBytes("UTF-8"));
        }
        android.system.Os.rename(temp.getAbsolutePath(), new File(root, "journal.json").getAbsolutePath());
    }

    @SuppressWarnings("MissingPermission") private void openCamera() {
        if (destroyed || camera != null || opening) { say(camera == null ? "活动已结束" : "相机已打开；可直接拍摄"); return; }
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA}, 7); say("请授予相机权限后再次点击"); return; }
            chooseMainCamera();
            focusWindowStartMs = System.currentTimeMillis(); focusEvidence = null; startClassicFileAudit();
            record("camera", new JSONObject().put("logicalId", logicalId).put("physicalId", physicalId).put("iso", 50).put("focus", getIntent().hasExtra("pairFocusDistance")?"autofocus_locked":"infinity").put("root", root.getAbsolutePath()));
            opening = true;
            manager.openCamera(logicalId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice c) { opening = false; camera = c; retryUntil = 0; say("CameraDevice 已长驻；正在配置预热会话"); if (autoHybrid) { autoHybrid = false; configure(Mode.MP50, true, 0, hybridModes()); } else if (probePhotoBridge) { probePhotoBridge = false; probePhotoPrivateBridge(); configure(Mode.LOFIC, false); } else configure((probeMode0 || autoMp50) ? Mode.MP50 : Mode.LOFIC, false); }
                public void onDisconnected(CameraDevice c) { opening = false; c.close(); camera = null; record("device", "disconnected"); say("相机断开"); retryOpenIfArmed(); }
                public void onError(CameraDevice c, int error) { opening = false; c.close(); camera = null; record("device_error", error); say("相机错误 " + error); retryOpenIfArmed(); }
            }, worker);
        } catch (Exception e) { opening = false; fail("open", e); retryOpenIfArmed(); }
    }

    private void retryOpenIfArmed() {
        if (destroyed || (!armLofic && !autoLofic && !autoMp50 && !autoHybrid) || retryUntil == 0) return;
        long left = retryUntil - SystemClock.elapsedRealtime();
        if (left <= 0) { retryUntil = 0; return; }
        worker.postDelayed(this::openCamera, Math.min(40L, left));
    }

    private void chooseMainCamera() throws Exception {
        if (requestedCameraId != null && requestedCameraId.length() > 0) {
            logicalId = requestedCameraId;
            sensor = manager.getCameraCharacteristics(logicalId);
            if (sensor == null) throw new IllegalStateException("请求的 cameraId 不存在：" + requestedCameraId);
            // Keep the logical device open and optionally bind its validated
            // physical main sensor, as Photo does for the CHI path.
            physicalId = requestedPhysicalId;
            if (physicalId != null && physicalId.length() > 0) {
                if (!sensor.getPhysicalCameraIds().contains(physicalId)) {
                    throw new IllegalStateException("logical camera " + logicalId + " 不包含 physical camera " + physicalId + "; available=" + sensor.getPhysicalCameraIds());
                }
                sensor = manager.getCameraCharacteristics(physicalId);
            } else physicalId = null;
            Range<Integer> requestedIso = sensor.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (requestedIso == null || requestedIso.getLower() != 50) throw new IllegalStateException("请求 cameraId 的最低 ISO 不是 50：" + requestedIso);
            return;
        }
        float best = -1f; logicalId = null; sensor = null;
        String[] ids = new String[0];
        for (int attempt = 0; attempt < 16 && logicalId == null; attempt++) {
            ids = manager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics c;
                try { c = manager.getCameraCharacteristics(id); }
                catch (Exception unavailable) { record("camera_id_unavailable", json("id", id, "error", unavailable.toString())); continue; }
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (!Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(facing)) continue;
                SizeF z = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE); float area = z == null ? 0 : z.getWidth() * z.getHeight();
                if (area > best) { best = area; logicalId = id; sensor = c; }
            }
            if (logicalId == null && attempt < 15) Thread.sleep(250L);
        }
        // Some CamX mode0 restarts briefly omit LENS_FACING.  The largest
        // available sensor is the validated main camera fallback.
        if (logicalId == null) {
            best = -1f;
            for (String id : ids) {
                CameraCharacteristics c;
                try { c = manager.getCameraCharacteristics(id); }
                catch (Exception unavailable) { continue; }
                SizeF z = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE); float area = z == null ? 0 : z.getWidth() * z.getHeight();
                if (area > best) { best = area; logicalId = id; sensor = c; }
            }
        }
        if (logicalId == null) throw new IllegalStateException("没有可用主摄，cameraIds=" + Arrays.toString(ids));
        selectPhysicalAndValidate();
    }

    private void selectPhysicalAndValidate() throws Exception {
        float best = -1f; physicalId = null;
        for (String id : sensor.getPhysicalCameraIds()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id); SizeF z = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float area = z == null ? 0 : z.getWidth() * z.getHeight(); if (area > best) { best = area; physicalId = id; sensor = c; }
        }
        Range<Integer> iso = sensor.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (iso == null || iso.getLower() != 50) throw new IllegalStateException("主摄最低 ISO 不是已验证的 50：" + iso);
    }

    /**
     * Read-only probe for Photo's private LocalParallelService bridge.  The
     * public Camera2 stream map has already rejected 8160x6144; this probe
     * checks whether the Photo APK classes and its MIVI runtime can construct
     * the same ImageReader descriptors in our process.  It does not submit a
     * capture request and closes every returned reader.
     */
    private void probePhotoPrivateBridge() {
        final long started = SystemClock.elapsedRealtime();
        final JSONObject report = json("startedAt", System.currentTimeMillis(),
                "package", "com.android.camera", "requestedWidth", PHOTO_RAW_SIZE.getWidth(),
                "requestedHeight", PHOTO_RAW_SIZE.getHeight(), "requestedFormat", ImageFormat.RAW_SENSOR,
                "requestedMaxImages", 1, "operationMode", PHOTO_50MP_OPERATION_MODE,
                "featureMask", 2048, "cameraId", logicalId, "physicalId", physicalId,
                "deviceOpen", camera != null, "readOnly", true);
        try {
            Context photo = createPackageContext("com.android.camera",
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader contextLoader = photo.getClassLoader();
            String apkPath = photo.getApplicationInfo().sourceDir;
            File dexCache = new File(getCodeCacheDir(), "photo-dex");
            dexCache.mkdirs();
            ClassLoader loader = new PhotoDexClassLoader(apkPath, dexCache.getAbsolutePath(),
                    photo.getApplicationInfo().nativeLibraryDir);
            report.put("photoContextClassLoader", String.valueOf(contextLoader));
            report.put("photoApk", apkPath);
            report.put("photoClassLoader", String.valueOf(loader));
            JSONArray classes = new JSONArray();
            String[] names = new String[]{
                    "com.xiaomi.camera.imagecodec.ReprocessorFactory",
                    "com.xiaomi.camera.imagecodec.ImagePool",
                    "com.xiaomi.camera.mivi.MIVISDKConfig",
                    "com.xiaomi.protocol.IImageReaderParameterSets",
                    "v6.d", "com.android.camera.b", "com.android.camera.b$b"};
            for (String name : names) {
                JSONObject item = json("name", name);
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    item.put("loaded", true).put("loader", String.valueOf(type.getClassLoader()));
                    item.put("constructors", constructorsJson(type));
                    item.put("methods", methodsJson(type));
                } catch (Throwable e) {
                    item.put("loaded", false).put("error", throwableText(e));
                }
                classes.put(item);
            }
            report.put("classes", classes);

            if (!unsafePhotoBridge) {
                report.put("probeStage", "class_visibility_only");
                report.put("bridgeSkipped", "unsafePhotoBridge=false; no Photo Application, MIVI, native library, or b$b instance created");
                report.put("elapsedMs", SystemClock.elapsedRealtime() - started);
                say("Photo 类可见性探测完成；未启动 Photo/MIVI 原生线程");
                record("photo_private_bridge", report);
                return;
            }

            Class<?> sdkType = Class.forName("com.xiaomi.camera.mivi.MIVISDKConfig", true, loader);
            Object sdk = sdkType.getMethod("getInstance").invoke(null);
            Application app = photo.getApplicationContext() instanceof Application
                    ? (Application) photo.getApplicationContext() : null;
            if (app == null) {
                String appName = photo.getApplicationInfo().className;
                if (appName == null || appName.length() == 0) appName = Application.class.getName();
                app = new Instrumentation().newApplication(loader, appName, photo);
                report.put("photoApplicationClass", app.getClass().getName());
                Class<?> globalType = Class.forName("com.xiaomi.camera.basic.Global", true, loader);
                Method globalInit = findMethod(globalType, "init", Application.class, boolean.class, String.class, int.class, String.class);
                if (globalInit == null) throw new NoSuchMethodException("Global.init");
                globalInit.invoke(null, app, false, "5.0.0", 1, "com.android.camera");
                report.put("photoGlobalInit", true);
            }
            if (app != null) {
                invokeIfPresent(sdkType, sdk, "init", new Class<?>[]{Application.class}, new Object[]{app}, report, "miviInit");
            } else {
                report.put("miviInit", "application_context_unavailable");
            }
            invokeIfPresent(sdkType, sdk, "setPackageName", new Class<?>[]{String.class}, new Object[]{"com.android.camera"}, report, "miviPackage");
            invokeIfPresent(sdkType, sdk, "setVirtualCameraIds", new Class<?>[]{String.class, String.class}, new Object[]{logicalId == null ? "2" : logicalId, physicalId == null ? (logicalId == null ? "2" : logicalId) : physicalId}, report, "miviVirtualIds");
            SparseArray<Object> characteristics = new SparseArray<>();
            if (sensor != null) characteristics.put(parseCameraNumber(logicalId), sensor);
            invokeIfPresent(sdkType, sdk, "setCameraCharacteristics", new Class<?>[]{SparseArray.class}, new Object[]{characteristics}, report, "miviCharacteristics");
            invokeIfPresent(sdkType, sdk, "setMockCameraIds", new Class<?>[]{List.class}, new Object[]{Collections.emptyList()}, report, "miviMockIds");

            Class<?> factoryType = Class.forName("com.xiaomi.camera.imagecodec.ReprocessorFactory", true, loader);
            Method init = findMethod(factoryType, "init", Context.class);
            if (init != null) {
                init.setAccessible(true);
                init.invoke(null, photo);
                report.put("reprocessorFactoryInit", true);
            } else report.put("reprocessorFactoryInit", "method_not_found");

            Class<?> bridgeType = Class.forName("com.android.camera.b", true, loader);
            Object bridge = bridgeType.getConstructor().newInstance();
            Method getLocal = bridgeType.getMethod("a");
            Object local = getLocal.invoke(bridge);
            if (local == null) {
                // b.a() has a device capability gate.  Photo itself reaches
                // the same implementation after its mode controller has
                // initialized, so instantiate the public inner bridge
                // directly to distinguish that gate from a missing runtime.
                Class<?> localType = Class.forName("com.android.camera.b$b", true, loader);
                local = localType.getConstructor(bridgeType).newInstance(bridge);
                report.put("localParallelServiceDirect", local != null);
            }
            report.put("localParallelService", local == null ? JSONObject.NULL : local.getClass().getName());
            if (local == null) throw new IllegalStateException("Photo b.a() returned null");

            Class<?> paramsType = Class.forName("com.xiaomi.protocol.IImageReaderParameterSets", true, loader);
            Object params = paramsType.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                    .newInstance(PHOTO_RAW_SIZE.getWidth(), PHOTO_RAW_SIZE.getHeight(), ImageFormat.RAW_SENSOR, 1, 0, 1);
            Method setPhysical = paramsType.getMethod("setPhysicCameraId", int.class);
            setPhysical.invoke(params, -1);
            Class<?> descriptorType = Class.forName("v6.d", true, loader);
            Object descriptor = descriptorType.getConstructor().newInstance();
            descriptorType.getField("a").setInt(descriptor, 55);
            descriptorType.getField("b").setInt(descriptor, -1);
            descriptorType.getField("c").setBoolean(descriptor, true);
            descriptorType.getField("d").setBoolean(descriptor, false);
            descriptorType.getField("f").set(descriptor, params);
            SparseArray<Object> requested = new SparseArray<>();
            requested.put(55, descriptor);
            Method makeReaders = local.getClass().getMethod("a", SparseArray.class, int.class, int.class);
            Object result = makeReaders.invoke(local, requested, bridge.hashCode(), camera == null ? 0 : camera.hashCode());
            report.put("bridgeReturned", result != null);
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started);
            report.put("returnedDescriptors", describePrivateDescriptors(result));
            closePrivateReaders(result);
            say("Photo 私有桥探测完成；请查看 journal.json 的 photo_private_bridge 记录");
        } catch (Throwable e) {
            try {
                report.put("bridgeReturned", false);
                report.put("elapsedMs", SystemClock.elapsedRealtime() - started);
                report.put("error", throwableText(e));
            } catch (Exception ignored) { }
            say("Photo 私有桥探测失败；失败证据已保留");
        }
        record("photo_private_bridge", report);
    }

    private int parseCameraNumber(String id) {
        try { return Integer.parseInt(id); } catch (Exception ignored) { return 2; }
    }

    private JSONArray constructorsJson(Class<?> type) {
        JSONArray out = new JSONArray();
        try { for (Constructor<?> c : type.getConstructors()) out.put(c.toGenericString()); }
        catch (Throwable e) { out.put("error: " + throwableText(e)); }
        return out;
    }

    private JSONArray methodsJson(Class<?> type) {
        JSONArray out = new JSONArray();
        try { for (Method m : type.getMethods()) if (m.getName().equals("a") || m.getName().equals("init") || m.getName().startsWith("set") || m.getName().equals("getInstance")) out.put(m.toGenericString()); }
        catch (Throwable e) { out.put("error: " + throwableText(e)); }
        return out;
    }

    private Method findMethod(Class<?> type, String name, Class<?>... args) {
        try { return type.getMethod(name, args); }
        catch (Exception ignored) {
            try { return type.getDeclaredMethod(name, args); }
            catch (Exception ignoredToo) { return null; }
        }
    }

    private void invokeIfPresent(Class<?> type, Object receiver, String name, Class<?>[] args, Object[] values, JSONObject report, String key) {
        try {
            Method m = type.getMethod(name, args);
            Object result = m.invoke(receiver, values);
            report.put(key, result == null ? JSONObject.NULL : String.valueOf(result));
        } catch (Throwable e) { try { report.put(key, "error: " + throwableText(e)); } catch (Exception ignored) {} }
    }

    private String throwableText(Throwable e) {
        Throwable t = e;
        if (t instanceof InvocationTargetException && ((InvocationTargetException) t).getCause() != null) t = ((InvocationTargetException) t).getCause();
        return t.getClass().getName() + ": " + String.valueOf(t.getMessage());
    }

    private JSONArray describePrivateDescriptors(Object value) {
        JSONArray out = new JSONArray();
        if (!(value instanceof SparseArray)) return out;
        SparseArray<?> array = (SparseArray<?>) value;
        for (int i = 0; i < array.size(); i++) {
            Object d = array.valueAt(i);
            JSONObject item = json("key", array.keyAt(i), "class", d == null ? JSONObject.NULL : d.getClass().getName());
            if (d != null) try {
                Class<?> t = d.getClass();
                item.put("a", t.getField("a").getInt(d)).put("b", t.getField("b").getInt(d));
                item.put("c", t.getField("c").getBoolean(d)).put("d", t.getField("d").getBoolean(d));
                Object p = t.getField("f").get(d);
                item.put("params", p == null ? JSONObject.NULL : p.toString());
                Object reader = t.getField("e").get(d);
                if (reader instanceof ImageReader) {
                    ImageReader r = (ImageReader) reader;
                    item.put("reader", json("width", r.getWidth(), "height", r.getHeight(), "format", r.getImageFormat(), "maxImages", r.getMaxImages(), "usage", r.getUsage()));
                }
            } catch (Throwable e) { try { item.put("error", throwableText(e)); } catch (Exception ignored) {} }
            try { out.put(item); } catch (Exception ignored) { }
        }
        return out;
    }

    private void closePrivateReaders(Object value) {
        if (!(value instanceof SparseArray)) return;
        SparseArray<?> array = (SparseArray<?>) value;
        for (int i = 0; i < array.size(); i++) try {
            Object d = array.valueAt(i); if (d == null) continue;
            Object reader = d.getClass().getField("e").get(d); if (reader instanceof ImageReader) ((ImageReader) reader).close();
        } catch (Throwable ignored) { }
    }

    private void sequenceModes(Mode[] modes) {
        if (camera == null) { say("请先打开并预热持久会话"); return; }
        if (modes.length == 0) return;
        configure(modes[0], true, 0, modes);
    }
    private void captureMode(Mode m) { if (camera == null) { say("请先打开并预热持久会话"); return; } configure(m, true, 0, null); }

    private int readMp50Format(int value) {
        for (int candidate : MP50_FORMATS) if (candidate == value) return value;
        record("invalid_mp50_format", json("requested", value, "fallback", DEFAULT_MP50_FORMAT));
        return DEFAULT_MP50_FORMAT;
    }

    private void configure(Mode mode, boolean shoot) { configure(mode, shoot, 0, null); }
    private void configure(Mode mode, boolean shoot, int index, Mode[] chain) {
        if (camera == null || destroyed) return;
        if (index > 0 && chainAborted) return;
        if (index == 0 && batchFrames.stream().anyMatch(f -> !f.lifecycle.finished() || f.ioPending)) {
            record("capture_blocked", "previous_batch_not_persisted"); return;
        }
        if (configuring || (pendingFrame != null && !pendingFrame.terminal && !pendingFrame.lifecycle.canHandoff())) {
            record("session_request_ignored", json("mode", mode.toString(), "reason", "configuration_in_progress"));
            say("会话正在切换，请等待当前切换完成");
            return;
        }
        final long generation = ++nextSessionGeneration;
        try {
            OutputSpec spec = findOutput(mode);
            if (spec == null) throw new IllegalStateException("HAL 未公开该模式所需 RAW_SENSOR 输出");
            Size size = spec.size;
            if (shoot && session != null && mode == configuredMode && size.equals(configuredSize) && spec.format == configuredFormat) { shoot(mode, size, index, chain); return; }
            final int rawMaxImages = mode == Mode.MP50 ? 2 : 3;
            final ImageReader nextRawReader;
            final boolean nativeRawProbe = mode == Mode.LOFIC
                    && getIntent().getBooleanExtra("nativeRawSurfaceProbe", false);
            if (nativeRawProbe && (spec.format != 324
                    || !getIntent().getBooleanExtra("previewOnly", false)))
                throw new IllegalArgumentException("Native RAW14 descriptor probe is preview-only");
            if (nativeRawProbe) {
                nextRawReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                        ImageFormat.PRIVATE, rawMaxImages, requestedMp50Usage);
            } else if (mode == Mode.MP50) {
                // Keep the RAW_SENSOR format visible to CameraService while
                // matching ManualRaw's CPU-readable usage flag. Dataspace is
                // recorded below; Builder can make the preview surface appear
                // as IMPLEMENTATION_DEFINED on this HAL.
                nextRawReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                        spec.format, rawMaxImages, requestedMp50Usage);
                record("raw_reader_properties", json("usage", nextRawReader.getUsage(), "dataSpace", nextRawReader.getDataSpace(), "hardwareBufferFormat", nextRawReader.getHardwareBufferFormat(), "imageFormat", nextRawReader.getImageFormat(), "width", nextRawReader.getWidth(), "height", nextRawReader.getHeight(), "requestedMp50Format", requestedMp50Format));
            } else {
                nextRawReader = getIntent().getBooleanExtra("loficMp50Usage", false)
                        ? ImageReader.newInstance(size.getWidth(), size.getHeight(), spec.format, rawMaxImages, requestedMp50Usage)
                        : ImageReader.newInstance(size.getWidth(), size.getHeight(), spec.format, rawMaxImages);
                record("lofic_reader_properties", json("usage", nextRawReader.getUsage(),
                        "dataSpace", nextRawReader.getDataSpace(), "hardwareBufferFormat", nextRawReader.getHardwareBufferFormat(),
                        "imageFormat", nextRawReader.getImageFormat(), "width", nextRawReader.getWidth(), "height", nextRawReader.getHeight()));
            }
            if (nativeRawProbe) {
                try {
                    if(getIntent().hasExtra("pairSessionId")) NativeRawSurfaceProbe.reinitializeAttached(nextRawReader);
                    else NativeRawSurfaceProbe.reinitialize(nextRawReader);
                    int[] geometry = NativeRawSurfaceProbe.configure(nextRawReader.getSurface(),
                            size.getWidth(), size.getHeight(), 324, 146931712);
                    record("native_raw_surface_geometry", json("generation", generation,
                            "geometry", JSONObject.wrap(geometry)));
                    if (geometry == null || geometry.length != 3 || geometry[0] != 0
                            || geometry[1] != 0 || geometry[2] != 324)
                        throw new IOException("native RAW14 geometry not accepted");
                } catch (LinkageError e) {
                    nextRawReader.close();
                    throw new IOException("RAW14 initialization denied; launch the bounded instrumentation with --no-hidden-api-checks", e);
                } catch (Exception e) {
                    nextRawReader.close();
                    throw new IOException("RAW14 initialization failed; no fallback stream permitted", e);
                }
                final int[] seen = {0};
                nextRawReader.setOnImageAvailableListener(r -> {
                    Image image = null;
                    try {
                        image = r.acquireNextImage();
                        if (image != null && seen[0]++ < (bridgeProbe() ? 16 : 3)) {
                            try (android.hardware.HardwareBuffer buffer = image.getHardwareBuffer()) {
                                record("native_raw_preview_descriptor", json("generation", generation,
                                        "timestampNs", image.getTimestamp(),
                                        "descriptorOrder", "width,height,layers,format,stride,usage",
                                        "descriptor", JSONObject.wrap(NativeRawSurfaceProbe.describe(buffer))));
                                if ((bridgeProbe() && (!getIntent().hasExtra("pairSessionId") || reverseHdrSent)) || (!bridgeProbe() && seen[0] == 1)) {
                                    byte[] pixels = NativeRawSurfaceProbe.copyRaw14(buffer);
                                    if (pixels == null || pixels.length != 22020096)
                                        throw new IOException("RAW14 buffer lock/size failed");
                                    File evidence = new File(root, "probe_raw14_" + image.getTimestamp() + ".raw");
                                    persistProbe(evidence, pixels, "native_raw_preview_saved", json("generation", generation,
                                            "timestampNs", image.getTimestamp(), "bytes", pixels.length,
                                            "path", evidence.getAbsolutePath(), "validatedHdr", false));
                                    if (bridgeProbe()) {
                                        for (int row = 0; row < 3072; row++) {
                                            boolean nonzero = false;
                                            for (int col = 0; col < 7168; col++) if (pixels[row * 7168 + col] != 0) { nonzero = true; break; }
                                            if (!nonzero) throw new IOException("HDR14 missing row; bridge blocked");
                                        }
                                        bridgeRaw14.add(image.getTimestamp()); bridgeTrySnapshot(generation);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) { fail("native_raw_preview", e); }
                    finally { if (image != null) image.close(); }
                }, worker);
            } else if (mode == Mode.MP50 && getIntent().getBooleanExtra("mp50PreviewProbe", false)) {
                if (!getIntent().getBooleanExtra("previewOnly", false))
                    throw new IllegalArgumentException("50MP probe requires bounded preview");
                attachMp50Probe(nextRawReader, generation);
            } else nextRawReader.setOnImageAvailableListener(r -> onImage(r, mode, size, generation), worker);
            final ImageReader nextCompanion;
            if (mode == Mode.LOFIC && getIntent().getBooleanExtra("loficCompanionProbe", false)) {
                // Round 12: the RAW10 companion is now also taken during real captures so
                // the same exposure yields RAW10 here and the 14-bit branch via the MCTFE dump.
                nextCompanion = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                        ImageFormat.RAW10, 3, requestedMp50Usage);
                final int[] received = {0};
                nextCompanion.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireNextImage();
                        if (image != null && received[0]++ < (bridgeProbe() ? 16 : 3)) {
                            Image.Plane plane = image.getPlanes()[0];
                            record("companion_preview_image", json("generation", generation,
                                    "timestampNs", image.getTimestamp(), "format", image.getFormat(),
                                    "width", image.getWidth(), "height", image.getHeight(),
                                    "rowStride", plane.getRowStride(), "pixelStride", plane.getPixelStride(),
                                    "bytes", plane.getBuffer().remaining()));
                            if (nativeRawProbe && ((bridgeProbe() && (!getIntent().hasExtra("pairSessionId") || reverseHdrSent)) || (!bridgeProbe() && received[0] == 1))) {
                                ByteBuffer source = plane.getBuffer().duplicate();
                                byte[] pixels = new byte[source.remaining()]; source.get(pixels);
                                File evidence = new File(root, "probe_raw10_" + image.getTimestamp() + ".raw");
                                persistProbe(evidence, pixels, "companion_preview_saved", json("generation", generation,
                                        "timestampNs", image.getTimestamp(), "bytes", pixels.length,
                                        "path", evidence.getAbsolutePath(), "validatedHdr", false));
                                if (bridgeProbe()) {
                                    FastHybridPackedRaw10Check valid = new FastHybridPackedRaw10Check(pixels, image.getWidth(), image.getHeight(), plane.getRowStride(), plane.getPixelStride());
                                    if (!valid.complete) throw new IOException("HDR10 incomplete; bridge blocked");
                                    if(tripodPair()&&!tripodSlowSent)tripodHistograms.put(image.getTimestamp(),NativeEttr.channelHistograms(pixels));
                                    bridgeRaw10.add(image.getTimestamp()); bridgeTrySnapshot(generation);
                                }
                            }
                        }
                    } catch (Exception e) { fail("companion_preview", e); }
                    finally { if (image != null) image.close(); }
                }, worker);
            } else nextCompanion = null;
            final ImageReader nextBridgeMp50;
            if (bridgeProbe()&&!classicHdr()) {
                if (!nativeRawProbe || mode != Mode.LOFIC || physicalId == null)
                    throw new IllegalArgumentException("bridge requires physical native HDR diagnostic");
                nextBridgeMp50 = ImageReader.newInstance(8192, 6144, ImageFormat.RAW10, 2, requestedMp50Usage);
                attachMp50Probe(nextBridgeMp50, generation);
            } else nextBridgeMp50 = null;
            final SurfaceTexture nextPreviewTexture;
            final Surface nextPreviewSurface;
            final SurfaceTexture nextDownscaleTexture;
            final Surface nextDownscaleSurface;
            final ArrayList<ImageReader> nextReaders = new ArrayList<>();
            if ((mode == Mode.MP50 || getIntent().getIntExtra("loficSessionType", 0) != 0) && requestedMp50AuxStreams && (requestedMp50YuvAux || requestedMp50GpuAux)) {
                ImageReader preview = makeDrainedPreviewReader(PHOTO_PREVIEW_SIZE);
                ImageReader downscale = makeDrainedPreviewReader(PHOTO_DOWNSCALE_SIZE);
                nextReaders.add(preview); nextReaders.add(downscale);
                nextPreviewTexture = null; nextDownscaleTexture = null;
                nextPreviewSurface = preview.getSurface(); nextDownscaleSurface = downscale.getSurface();
            } else if (mode == Mode.MP50 && requestedMp50AuxStreams) {
                nextPreviewTexture = new SurfaceTexture(0);
                nextPreviewTexture.setDefaultBufferSize(PHOTO_PREVIEW_SIZE.getWidth(), PHOTO_PREVIEW_SIZE.getHeight());
                nextPreviewSurface = new Surface(nextPreviewTexture);
                nextDownscaleTexture = new SurfaceTexture(0);
                nextDownscaleTexture.setDefaultBufferSize(PHOTO_DOWNSCALE_SIZE.getWidth(), PHOTO_DOWNSCALE_SIZE.getHeight());
                nextDownscaleSurface = new Surface(nextDownscaleTexture);
            } else if (mode == Mode.LOFIC && getIntent().getBooleanExtra("loficPreview", false)) {
                ImageReader preview = makeDrainedPreviewReader(PHOTO_PREVIEW_SIZE);
                nextReaders.add(preview);
                nextPreviewTexture = null; nextDownscaleTexture = null;
                nextPreviewSurface = preview.getSurface(); nextDownscaleSurface = null;
            } else {
                nextPreviewTexture = null;
                nextPreviewSurface = null;
                nextDownscaleTexture = null;
                nextDownscaleSurface = null;
            }
            final CameraCaptureSession oldSession = session;
            final ArrayList<ImageReader> oldReaders = new ArrayList<>(readers);
            ArrayList<OutputConfiguration> outs = new ArrayList<>();
            if (nextPreviewSurface != null) {
                OutputConfiguration previewOutput = new OutputConfiguration(nextPreviewSurface);
                if (mode == Mode.MP50 && physicalId != null && getIntent().getBooleanExtra("nativeSnapshotProbe", false))
                    previewOutput.setPhysicalCameraId(physicalId);
                outs.add(previewOutput);
            }
            if (nextDownscaleSurface != null) {
                OutputConfiguration downscaleOutput = new OutputConfiguration(nextDownscaleSurface);
                // This is SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_DOWNSCALE
                // from mivivendorstreamconfig.json (stream_prop=27).
                downscaleOutput.setStreamUseCase(PHOTO_DOWNSCALE_USE_CASE);
                outs.add(downscaleOutput);
            }
            if (nextBridgeMp50 != null) {
                nextReaders.add(nextBridgeMp50);
                OutputConfiguration fullRaw = new OutputConfiguration(nextBridgeMp50.getSurface());
                fullRaw.setPhysicalCameraId(physicalId); fullRaw.setStreamUseCase(524548L); outs.add(fullRaw);
            }
            nextReaders.add(nextRawReader);
            OutputConfiguration rawOutput = new OutputConfiguration(nextRawReader.getSurface());
            if (mode == Mode.MP50 && getIntent().getBooleanExtra("mp50MaximumResolution", false))
                rawOutput.addSensorPixelModeUsed(CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            if (mode == Mode.MP50 && requestedMp50SessionType == 0x900e) rawOutput.setStreamUseCase(524547L);
            if (mode == Mode.MP50 && getIntent().hasExtra("mp50StreamUseCase"))
                rawOutput.setStreamUseCase(getIntent().getLongExtra("mp50StreamUseCase", 0));
            if (mode == Mode.LOFIC && getIntent().hasExtra("loficStreamUseCase")) {
                long useCase = getIntent().getLongExtra("loficStreamUseCase", 0);
                rawOutput.setStreamUseCase(useCase);
                record("lofic_stream_use_case", json("generation", generation, "requested", useCase));
            }
            if (mode != Mode.MP50) record("physical_stream_id", json("generation", generation,
                    "physicalId", physicalId == null ? "" : physicalId, "skipped", skipPhysicalStreamId));
            if ((mode != Mode.MP50 || getIntent().getBooleanExtra("mp50PreviewProbe", false)
                    || getIntent().getBooleanExtra("mp50PhysicalRaw", false))
                    && physicalId != null && !skipPhysicalStreamId) rawOutput.setPhysicalCameraId(physicalId);
            // Diagnostic: leave the main RAW stream unattached so the vendor port
            // can use its own internal buffers (sizes then follow the forced sensor mode).
            if (mode == Mode.LOFIC && getIntent().getBooleanExtra("loficNoMainRaw", false))
                record("lofic_main_raw_detached", json("generation", generation, "rawWidth", size.getWidth(), "rawHeight", size.getHeight()));
            else outs.add(rawOutput);
            if (nextCompanion != null) {
                nextReaders.add(nextCompanion);
                OutputConfiguration companionOutput = new OutputConfiguration(nextCompanion.getSurface());
                companionOutput.setStreamUseCase(524554L);
                if (physicalId != null && !skipPhysicalStreamId) companionOutput.setPhysicalCameraId(physicalId);
                outs.add(companionOutput);
            }
            int sessionType = mode == Mode.MP50 ? requestedMp50SessionType : getIntent().getIntExtra("loficSessionType", 0);
            SessionConfiguration cfg = new SessionConfiguration(sessionType, outs, (Executor)r -> worker.post(r), new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) {
                    if (generation != nextSessionGeneration || destroyed) {
                        s.close(); closeReaders(nextReaders);
                        record("stale_session_configured", json("mode", mode.toString(), "generation", generation, "currentGeneration", nextSessionGeneration));
                        return;
                    }
                    configuring = false;
                    session = s;
                    activeSessionGeneration = generation;
                    configuredMode = mode;
                    configuredSize = size;
                    configuredFormat = spec.format;
                    readers.clear(); readers.addAll(nextReaders);
                    rawReader = nextRawReader;
                    bridgeMp50Reader = nextBridgeMp50;
                    Surface oldPreviewSurface = previewSurface;
                    SurfaceTexture oldPreviewTexture = previewTexture;
                    Surface oldDownscaleSurface = downscaleSurface;
                    SurfaceTexture oldDownscaleTexture = downscaleTexture;
                    previewSurface = nextPreviewSurface;
                    previewTexture = nextPreviewTexture;
                    downscaleSurface = nextDownscaleSurface;
                    downscaleTexture = nextDownscaleTexture;
                    if (oldSession != null && oldSession != s) oldSession.close();
                    closeReaders(oldReaders);
                    if (oldPreviewSurface != null) oldPreviewSurface.release();
                    if (oldPreviewTexture != null) oldPreviewTexture.release();
                    if (oldDownscaleSurface != null) oldDownscaleSurface.release();
                    if (oldDownscaleTexture != null) oldDownscaleTexture.release();
                    boolean fire = shoot || pendingAutoShoot || (autoLofic && mode == Mode.LOFIC);
                    if (fire && mode == Mode.LOFIC) autoLofic = false;
                    pendingAutoShoot = false;
                    record("session_configured", json("mode", mode.toString(), "generation", generation, "sessionType", sessionType, "rawWidth", size.getWidth(), "rawHeight", size.getHeight(), "rawFormat", spec.format, "requestedMp50Format", mode == Mode.MP50 ? requestedMp50Format : 0, "privateWidth", nextPreviewSurface == null ? 0 : PHOTO_PREVIEW_SIZE.getWidth(), "privateHeight", nextPreviewSurface == null ? 0 : PHOTO_PREVIEW_SIZE.getHeight(), "downscaleWidth", nextDownscaleSurface == null ? 0 : PHOTO_DOWNSCALE_SIZE.getWidth(), "downscaleHeight", nextDownscaleSurface == null ? 0 : PHOTO_DOWNSCALE_SIZE.getHeight(), "previewSurface", nextPreviewSurface != null, "downscaleSurface", nextDownscaleSurface != null, "outputCount", outs.size(), "sessionOperation", sessionOperation, "shoot", fire, "deviceKeptOpen", true));
                    if (fire && getIntent().getBooleanExtra("rawWarmup", false)) warmAndShoot(mode, size, index, chain, generation);
                    else if (fire && nextPreviewSurface != null && ((mode == Mode.MP50 && (requestedMp50YuvAux || requestedMp50GpuAux))
                            || (mode == Mode.LOFIC && getIntent().getBooleanExtra("loficPreview", false)))) warmAndShoot(mode, size, index, chain, generation);
                    else if (fire) shoot(mode, size, index, chain);
                    else if (probeMode0 && mode == Mode.MP50) { probeMode0 = false; shoot(mode, size, index, chain); }
                    else if (autoLofic && mode == Mode.LOFIC) { autoLofic = false; shoot(mode, size, index, chain); }
                    else say("会话已预热；设备保持打开");
                }
                public void onConfigureFailed(CameraCaptureSession s) {
                    s.close(); closeReaders(nextReaders);
                    if (nextPreviewSurface != null) nextPreviewSurface.release();
                    if (nextPreviewTexture != null) nextPreviewTexture.release();
                    if (nextDownscaleSurface != null) nextDownscaleSurface.release();
                    if (nextDownscaleTexture != null) nextDownscaleTexture.release();
                    if (generation != nextSessionGeneration) return;
                    configuring = false;
                    record("session_configure_failed", json("mode", mode.toString(), "generation", generation, "sessionType", sessionType, "rawWidth", size.getWidth(), "rawHeight", size.getHeight(), "rawFormat", spec.format, "requestedMp50Format", mode == Mode.MP50 ? requestedMp50Format : 0));
                    say(mode + " 会话被 HAL 拒绝；已保留失败记录");
                    record("chain_stopped", "session_configuration_failed");
                }
            });
            CaptureRequest.Builder params = request(mode, CameraDevice.TEMPLATE_PREVIEW);
            CaptureRequest sessionParams = params.build();
            record("session_params_built", requestSnapshot(sessionParams, mode, generation));
            cfg.setSessionParameters(sessionParams);
            record("session_request", new JSONObject().put("mode", mode.toString()).put("generation", generation).put("sessionType", sessionType).put("rawWidth", size.getWidth()).put("rawHeight", size.getHeight()).put("rawFormat", spec.format).put("requestedMp50Format", mode == Mode.MP50 ? requestedMp50Format : 0).put("privateWidth", nextPreviewSurface == null ? 0 : PHOTO_PREVIEW_SIZE.getWidth()).put("privateHeight", nextPreviewSurface == null ? 0 : PHOTO_PREVIEW_SIZE.getHeight()).put("downscaleWidth", nextDownscaleSurface == null ? 0 : PHOTO_DOWNSCALE_SIZE.getWidth()).put("downscaleHeight", nextDownscaleSurface == null ? 0 : PHOTO_DOWNSCALE_SIZE.getHeight()).put("previewSurface", nextPreviewSurface != null).put("downscaleSurface", nextDownscaleSurface != null).put("outputCount", outs.size()).put("sessionOperation", sessionOperation).put("deviceKeptOpen", true));
            configuring = true;
            if (index > 0 && oldSession != null && getIntent().getBooleanExtra("abortAfterRaw", false)) {
                record("session_drain_after_raw", json("index", index, "phase", "begin"));
                oldSession.abortCaptures();
                record("session_drain_after_raw", json("index", index, "phase", "returned"));
            }
            camera.createCaptureSession(cfg);
        } catch (Exception e) { if (generation == nextSessionGeneration) configuring = false; fail("configure_" + mode, e); }
    }

    private ImageReader makeDrainedPreviewReader(Size size) {
        // Only the display preview is opaque. The vendor downscale/ASD
        // path declares YUV_420_888 in mivivendorstreamconfig.json.
        boolean gpuPreview = requestedMp50GpuAux && size.equals(PHOTO_PREVIEW_SIZE);
        ImageReader reader = gpuPreview
                ? ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.PRIVATE, 3,
                        android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                : ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3,
                        android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN |
                        (requestedMp50GpuAux ? android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE : 0));
        record("aux_reader_properties", json("width", size.getWidth(), "height", size.getHeight(),
                "format", reader.getImageFormat(), "usage", reader.getUsage(), "gpuAux", gpuPreview));
        reader.setOnImageAvailableListener(r -> { Image image = null; try { image = r.acquireLatestImage(); } catch (IllegalStateException ignored) { } finally { if (image != null) image.close(); } }, worker);
        return reader;
    }

    private void warmAndShoot(Mode mode, Size size, int index, Mode[] chain, long generation) {
        final CameraCaptureSession warmingSession = session;
        final boolean[] done = {false};
        final int[] completedFrames = {0};
        final int warmFrames=classicHdr()?4:8;
        final boolean overlapFocus=getIntent().hasExtra("pairSessionId")||single50();
        final java.util.concurrent.FutureTask<Void> focusTask=new java.util.concurrent.FutureTask<>(()->{verifyFactoryInfinity();return null;});
        try {
            final boolean snapshotProbe = getIntent().getBooleanExtra("nativeSnapshotProbe", false);
            if (snapshotProbe && ((!getIntent().getBooleanExtra("nativeRawSurfaceProbe", false)
                    && !getIntent().getBooleanExtra("mp50PreviewProbe", false))
                    || !getIntent().getBooleanExtra("previewOnly", false) || previewSurface == null))
                throw new IllegalArgumentException("Snapshot probe requires native diagnostic and display warmup");
            boolean zslWarmup = getIntent().getBooleanExtra("nativeZslWarmup", false);
            if (zslWarmup && ((!getIntent().getBooleanExtra("nativeRawSurfaceProbe", false)
                    && !getIntent().getBooleanExtra("mp50PreviewProbe", false))
                    || !getIntent().getBooleanExtra("previewOnly", false) || snapshotProbe))
                throw new IllegalArgumentException("ZSL warmup is limited to bounded native diagnostics");
            CaptureRequest.Builder preview = request(mode, zslWarmup
                    ? CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG : CameraDevice.TEMPLATE_PREVIEW);
            preview.set(CaptureRequest.CONTROL_CAPTURE_INTENT, zslWarmup
                    ? CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG : CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
            record("warmup_intent", json("zsl", zslWarmup, "generation", generation));
            boolean mainRawDetached = mode == Mode.LOFIC && getIntent().getBooleanExtra("loficNoMainRaw", false);
            boolean nativeRawOnly = getIntent().getBooleanExtra("mp50PreviewProbe", false)
                    || getIntent().getBooleanExtra("mp50PhysicalRaw", false)
                    || (getIntent().getBooleanExtra("nativeRawSurfaceProbe", false)
                    && getIntent().getBooleanExtra("nativeRawOnlyTargets", false));
            preview.addTarget((snapshotProbe || !nativeRawOnly) && previewSurface != null ? previewSurface : rawReader.getSurface());
            if (!snapshotProbe && mode == Mode.LOFIC && getIntent().getBooleanExtra("loficCompanionProbe", false)) {
                if (!nativeRawOnly && previewSurface != null && !mainRawDetached) preview.addTarget(rawReader.getSurface());
                for (ImageReader candidate : readers)
                    if (candidate != bridgeMp50Reader && candidate != rawReader && candidate.getImageFormat() == ImageFormat.RAW10)
                        preview.addTarget(candidate.getSurface());
            }
            // A CPU-readable downscale target is classified as a snapshot
            // by this vendor policy. Repeating requests target display only.
            warmingSession.setRepeatingRequest(preview.build(), new CameraCaptureSession.CaptureCallback() {
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    if (done[0] || !isActive(generation, s)) return;
                    done[0] = true;
                    try { s.stopRepeating(); } catch (Exception ignored) { }
                    fail("preview_capture_failed", new IllegalStateException("preview request rejected; reason=" + failure.getReason() + "; shutter not issued"));
                }
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    if (done[0] || !isActive(generation, s)) return;
                    int frames = ++completedFrames[0];
                    if (getIntent().getBooleanExtra("nativeRawSurfaceProbe", false)
                            || getIntent().getBooleanExtra("mp50PreviewProbe", false)) try {
                        record("native_probe_result", resultJson(result, mode, size, frames, System.nanoTime())
                                .put("generation", generation).put("frameNumber", result.getFrameNumber()));
                    } catch (Exception e) { fail("native_probe_metadata", e); }
                    if (frames == 1) record("preview_first_result", json("generation", generation,
                            "timestampNs", result.get(CaptureResult.SENSOR_TIMESTAMP),
                            "iso", result.get(CaptureResult.SENSOR_SENSITIVITY),
                            "focusDistance", result.get(CaptureResult.LENS_FOCUS_DISTANCE)));
                    if(frames==(classicHdr()?3:4) && overlapFocus){Thread audit=new Thread(focusTask,"JC-Focus-Audit");audit.setDaemon(true);audit.start();}
                    if (frames != warmFrames) return;
                    done[0] = true;
                    record("preview_warmed", json("frames", frames, "generation", generation, "timestampNs", result.get(CaptureResult.SENSOR_TIMESTAMP), "actual", captureKeys(result)));
                    try {
                        s.stopRepeating();
                        if(overlapFocus)focusTask.get(10,java.util.concurrent.TimeUnit.SECONDS);else verifyFactoryInfinity();
                        JSONObject actual = resultJson(result, mode, size, sequence + 1, System.nanoTime());
                        long timestamp = actual.optLong("timestampNs", -1);
                        if (!captureMetadataMatches(actual, timestamp, timestamp)) {
                            record("preview_parameters_rejected", actual);
                            fail("preview_parameters", new IllegalStateException("actual ISO, focus, or metadata not verified; shutter not issued"));
                            return;
                        }
                        if (bridgeProbe()) { if (reverseBridge()) reverseBegin(generation); else bridgeAcquireAnchor(generation); return; }
                        if (snapshotProbe) {
                            if(single50()){
                                long deadline=getIntent().getLongExtra("native50NotBeforeMs",0);
                                if(deadline<=0||deadline-SystemClock.elapsedRealtime()>15000)throw new IOException("invalid shutter delay deadline");
                                worker.postDelayed(()->{try{if(!isActive(generation,s)||destroyed||new File(root.getParentFile(),"cancel.json").isFile())throw new IOException("capture cancelled before delayed shutter");nativeSnapshot(s,mode,size,generation);}catch(Exception e){fail("delayed_shutter",e);}},Math.max(0,deadline-SystemClock.elapsedRealtime()));
                            }else nativeSnapshot(s, mode, size, generation);
                            return;
                        }
                        if (getIntent().getBooleanExtra("previewOnly", false)) {
                            record("preview_only_complete", actual);
                            say("预览诊断完成；没有发出快照请求"); return;
                        }
                        shoot(mode, size, index, chain);
                    } catch (Exception e) { fail("preview_stop", e); }
                }
            }, worker);
            worker.postDelayed(() -> {
                if (!done[0] && isActive(generation, warmingSession)) {
                    done[0] = true;
                    try { warmingSession.stopRepeating(); } catch (Exception ignored) { }
                    fail("preview_warmup", new IllegalStateException("preview did not reach " + warmFrames + " results within deadline; received=" + completedFrames[0] + "; shutter not issued"));
                }
            }, (single50()||classicHdr())?Math.max(4000,2000+warmFrames*getIntent().getLongExtra("sensorExposureNs",1000000)/1000000):4000);
        } catch (Exception e) { done[0] = true; fail("preview_start", e); }
    }

    private void persistProbe(File file, byte[] pixels, String event, JSONObject value) throws Exception {
        if (!bridgeFast()) {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(pixels); }
            if (!record(event, value)) throw new IOException("RAW evidence journal failed");
            return;
        }
        writer.execute(() -> {
            try {
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(pixels); }
                if(classicHdr())value.put("sha256",Native50Store.hash(file));
                worker.post(() -> { if (!record(event, value)) fail("probe_persist", new IOException("RAW evidence journal failed")); });
            } catch (Exception e) { worker.post(() -> fail("probe_persist", e)); }
        });
    }

    private void attachMp50Probe(ImageReader reader, long generation) {
                final boolean[] saved = {false};
                reader.setOnImageAvailableListener(r -> {
                    Image image = null;
                    try {
                        image = r.acquireNextImage();
                        if (image == null || saved[0]) return;
                        saved[0] = true;
                        Image.Plane plane = image.getPlanes()[0];
                        ByteBuffer buffer = plane.getBuffer(); byte[] pixels = new byte[buffer.remaining()];
                        buffer.get(pixels);
                        FastHybridFrameCheck check = new FastHybridFrameCheck(pixels, image.getWidth(), image.getHeight(),
                                plane.getRowStride(), plane.getPixelStride(), image.getFormat());
                        FastHybridPackedRaw10Check packed = image.getFormat() == ImageFormat.RAW10
                                ? new FastHybridPackedRaw10Check(pixels, image.getWidth(), image.getHeight(), plane.getRowStride(), plane.getPixelStride()) : null;
                        File evidence = new File(root, "probe_mp50_" + image.getTimestamp() + ".raw");
                        try (FileOutputStream out = new FileOutputStream(evidence)) { out.write(pixels); }
                        record("mp50_preview_saved", json("generation", generation, "timestampNs", image.getTimestamp(),
                                "width", image.getWidth(), "height", image.getHeight(), "format", image.getFormat(),
                                "rowStride", plane.getRowStride(), "pixelStride", plane.getPixelStride(),
                                "bytes", pixels.length, "payloadComplete", packed == null ? check.complete : packed.complete,
                                "layoutValid", packed == null ? check.layoutValid : packed.layoutValid,
                                "nonzeroRows", packed == null ? check.nonzeroRows : packed.nonzeroRows,
                                "minimum", packed == null ? check.minimum : packed.minimum,
                                "maximum", packed == null ? check.maximum : packed.maximum, "path", evidence.getAbsolutePath()));
                        if (reverseBridge()) {
                            if (packed == null || !packed.complete) throw new IOException("50MP payload rejected; no HDR shutter");
                            reverseMp50Pixels = image.getTimestamp(); reverseTryHdr(generation);
                        }
                    } catch (Exception e) { fail("mp50_preview", e); }
                    finally { if (image != null) image.close(); }
                }, worker);
    }

    private void bridgeAcquireAnchor(long generation) throws Exception {
        if(classicHdr()){reverseHdrSent=true;pairHdrExposure=getIntent().getLongExtra("sensorExposureNs",33333333);}
        if (bridgeFast() && !reverseBridge() && !classicHdr()) {
            bridgePreparedSnapshot = buildNativeSnapshot(Mode.MP50);
            String helper = getIntent().getBooleanExtra("bridgeFastJava", false)
                    ? "CLASSPATH=" + RootProcess.quote(getApplicationInfo().sourceDir)
                        + " /system/bin/app_process /system/bin local.jc.mainraw.FastHybridBridgePropertyMain"
                    : FastHybridBridgeTransition.PREPARE;
            bridgePreparedTransition = new FastHybridBridgeTransition(RootProcess.start(helper, true));
            record("bridge_prepared", json("generation", generation));
        }
        CaptureRequest.Builder b = request(Mode.LOFIC, CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(rawReader.getSurface());
        for (ImageReader r : readers)
            if (r != bridgeMp50Reader && r != rawReader && r.getImageFormat() == ImageFormat.RAW10) b.addTarget(r.getSurface());
        final boolean quick=tripodPair()&&!tripodSlowSent;
        final String phase=quick?"tripod_quick":"bridge_hdr";
        final long requestedExposure=pairHdrExposure;
        record(phase+"_request", json("generation", generation,"requestedExposureNs",requestedExposure));
        session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r, long timestamp, long frame) {
                record(phase+"_started", json("timestampNs", timestamp, "frameNumber", frame));
            }
            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                if (!isActive(generation, s)) return;
                try {
                    JSONObject actual = resultJson(result, Mode.LOFIC, LOFIC_RAW_SIZE, 1, System.nanoTime());
                    long ts = actual.optLong("timestampNs", -1);
                    if (!captureMetadataMatches(actual, ts, ts) || actual.optInt("actualSensorMode", -1) != 5
                            || !actual.optBoolean("driverMetadataVerified", false))
                        {record("rejected_hdr_metadata",actual);throw new IOException("fresh HDR anchor metadata rejected; no retry");}
                    if (reverseBridge() && !(flexiblePair()?PairExposurePolicy.hdrMatches(requestedExposure,actual.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0)):PairExposurePolicy.fixedHdr(actual.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0))))
                        throw new IOException("HDR fixed safety shutter rejected");
                    actual.put("pairRequestedExposureNs",requestedExposure);
                    record(phase+"_result", actual);
                    if(quick){tripodQuick=actual;tripodAdvance(generation);return;}
                    bridgeAnchorTimestamp = ts;
                    bridgeTrySnapshot(generation);
                } catch (Exception e) { fail("bridge_anchor", e); }
            }
            @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                fail("bridge_anchor", new IOException("HDR anchor rejected; no retry"));
            }
        }, worker);
    }

    private void bridgeTrySnapshot(long generation) throws Exception {
        if(classicHdr()){classicExposureDone();return;}
        if (reverseBridge()) {tripodAdvance(generation);return;}
        if (!bridgeProbe() || bridgeSent || bridgeAnchorTimestamp <= 0
                || !bridgeRaw10.contains(bridgeAnchorTimestamp) || !bridgeRaw14.contains(bridgeAnchorTimestamp)) return;
        if (!isActive(generation, session)) throw new IOException("bridge generation expired");
        bridgeSent = true;
        long transitionStart = SystemClock.elapsedRealtimeNanos();
        String readback;
        if (bridgeFast()) {
            if (bridgePreparedTransition == null) throw new IOException("bridge helper absent");
            readback = bridgePreparedTransition.apply();
            bridgePreparedTransition.close(); bridgePreparedTransition = null;
        } else {
        java.lang.Process process = RootProcess.start("setprop persist.vendor.sat.forceModeSele 0; "
                + "setprop persist.vendor.sat.binningModeW 0; setprop vendor.debug.camera.miaec.auto_hdr_mode 0; "
                + "getprop persist.vendor.sat.forceModeSele; getprop persist.vendor.sat.binningModeW; getprop vendor.debug.camera.miaec.auto_hdr_mode", true);
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { process.destroy(); throw new IOException("bridge property transition timed out; no shutter"); }
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[1024]; int n;
        while ((n = process.getInputStream().read(chunk)) != -1) output.write(chunk, 0, n);
        readback = new String(output.toByteArray(), StandardCharsets.UTF_8).replace("\r", "").trim();
        if (process.exitValue() != 0 || !readback.equals("0\n0\n0")) throw new IOException("bridge property readback rejected: " + readback);
        }
        record("bridge_mode_transition", json("hdrTimestampNs", bridgeAnchorTimestamp, "properties", readback, "transitionElapsedNs", SystemClock.elapsedRealtimeNanos() - transitionStart));
        nativeSnapshot(session, Mode.MP50, PHOTO_RAW_SIZE, generation);
    }

    private CaptureRequest buildNativeSnapshot(Mode mode) throws Exception {
        CaptureRequest.Builder b = request(mode, CameraDevice.TEMPLATE_STILL_CAPTURE);
        b.addTarget(bridgeProbe() && mode == Mode.MP50 ? bridgeMp50Reader.getSurface() : rawReader.getSurface());
        if (!bridgeProbe()) for (ImageReader reader : readers)
            if (reader != rawReader && reader.getImageFormat() == ImageFormat.RAW10) b.addTarget(reader.getSurface());
        b.set(new CaptureRequest.Key<byte[]>("xiaomi.snapshot.imageName", byte[].class),
                ("JC_NATIVE_" + System.nanoTime() + "\0").getBytes(StandardCharsets.UTF_8));
        return b.build();
    }

    private void nativeSnapshot(CameraCaptureSession target, Mode mode, Size size, long generation) throws Exception {
        CaptureRequest capture = bridgeFast() ? bridgePreparedSnapshot : buildNativeSnapshot(mode);
        if (capture == null) throw new IOException("prepared snapshot absent");
        if (!record("native_snapshot_request", requestSnapshot(capture, mode, generation)))
            throw new IOException("snapshot evidence journal unavailable");
        final long submittedNs = System.nanoTime();
        if(single50())single50ShutterIssued=true;
        target.capture(capture, new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r, long timestamp, long frame) {
                record("native_snapshot_started", json("generation", generation, "timestampNs", timestamp, "frameNumber", frame));
            }
            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                try {
                    JSONObject snapshotResult = resultJson(result, mode, size, 1, submittedNs)
                            .put("generation", generation).put("frameNumber", result.getFrameNumber()).put("snapshot", true);
                    record("native_probe_result", snapshotResult);
                    if (reverseBridge()) { reverseMp50Result = snapshotResult; reverseTryHdr(generation); }
                    record("native_snapshot_result", json("generation", generation, "timestampNs", result.get(CaptureResult.SENSOR_TIMESTAMP)));
                } catch (Exception e) { fail("native_snapshot_metadata", e); }
            }
            @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                fail("native_snapshot_failed", new IOException("reason=" + failure.getReason() + "; no retry"));
            }
        }, worker);
    }

    private OutputSpec findOutput(Mode mode) {
        StreamConfigurationMap map = sensor.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP); if (map == null) return null;
        Size[] all = map.getOutputSizes(ImageFormat.RAW_SENSOR);
        Size[] high = map.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR);
        boolean loficFullSize = mode == Mode.LOFIC && getIntent().getBooleanExtra("loficRawFullSize", false);
        Size wanted = (mode == Mode.MP50 || loficFullSize) ? PHOTO_RAW_SIZE : LOFIC_RAW_SIZE;
        int wantedFormat = mode == Mode.MP50 ? requestedMp50Format
                : getIntent().getIntExtra("loficRawFormat", ImageFormat.RAW_SENSOR);
        if (mode == Mode.LOFIC && wantedFormat != ImageFormat.RAW_SENSOR
                && wantedFormat != ImageFormat.RAW10 && wantedFormat != ImageFormat.RAW12
                && !(wantedFormat == 324 && getIntent().getBooleanExtra("previewOnly", false)))
            throw new IllegalArgumentException("Unsupported LOFIC diagnostic format: " + wantedFormat);
        if (all != null) for (Size s : all) if (s.equals(wanted)) return new OutputSpec(s, wantedFormat);
        if (high != null) for (Size s : high) if (s.equals(wanted)) return new OutputSpec(s, wantedFormat);
        // Xiaomi exposes ManualRaw 8192x6144 through its vendor scaler table
        // even when Camera2 omits it from the standard RAW_SENSOR map. Force
        // the size for MP50 so createCaptureSession can test that private
        // route; a configure failure is kept as evidence.
        record("raw_output_sizes", json("mode", mode.toString(), "wanted", wanted.toString(), "wantedFormat", wantedFormat, "available", sizesJson(all), "highResolution", sizesJson(high), "forcedVendorSize", mode == Mode.MP50 || loficFullSize));
        if (mode == Mode.MP50 || loficFullSize) return new OutputSpec(wanted, wantedFormat);
        return null;
    }

    private JSONArray sizesJson(Size[] values) {
        JSONArray out = new JSONArray();
        if (values != null) for (Size value : values) out.put(value.toString());
        return out;
    }

    private Mode[] hybridModes() {
        String spec = getIntent().getStringExtra("hybridOps");
        record("hybrid_chain_parsed", json("spec", spec == null ? "<null>" : spec));
        if (spec == null || spec.isEmpty()) return new Mode[]{Mode.MP50, Mode.LOFIC};
        String[] parts = spec.split(",");
        Mode[] modes = new Mode[parts.length];
        for (int i = 0; i < parts.length; i++)
            modes[i] = parts[i].trim().toUpperCase(java.util.Locale.US).startsWith("LOFIC") ? Mode.LOFIC : Mode.MP50;
        record("hybrid_chain_built", json("length", modes.length));
        return modes;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setPhysicalRequestValue(CaptureRequest.Builder builder, CaptureRequest.Key key, Object value) {
        builder.setPhysicalCameraKey(key, value, physicalId);
    }
    private CaptureRequest.Builder request(Mode mode) throws Exception {
        return request(mode, CameraDevice.TEMPLATE_STILL_CAPTURE);
    }
    private CaptureRequest.Builder request(Mode mode, int template) throws Exception {
        boolean physicalSettings = getIntent().getBooleanExtra("physicalRequestSettings", false);
        if (physicalSettings && (physicalId == null || skipPhysicalStreamId))
            throw new IllegalArgumentException("Physical settings require an attached physical stream");
        CaptureRequest.Builder b = physicalSettings
                ? camera.createCaptureRequest(template, Collections.singleton(physicalId))
                : camera.createCaptureRequest(template);
        long diagnosticExposureNs = reverseHdrSent?pairHdrExposure:getIntent().getLongExtra("sensorExposureNs", 1_000_000L);
        if (diagnosticExposureNs < 100_000L) diagnosticExposureNs = 100_000L;
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF); b.set(CaptureRequest.SENSOR_SENSITIVITY, 50); b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, diagnosticExposureNs);
        b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100);
        if (mode == Mode.MP50 && getIntent().getBooleanExtra("mp50MaximumResolution", false))
            b.set(CaptureRequest.SENSOR_PIXEL_MODE, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        // Diagnostic comparison with the native MIAEC path. This option did
        // not fix empty HDR metadata; actual-frame gates remain mandatory.
        if (mode == Mode.LOFIC && getIntent().getBooleanExtra("vendorManualAe", false))
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        long maxFrame = sensor.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION); long wantedFrame = getIntent().getLongExtra("sensorFrameDurationNs", 33_333_333L); long frame = Math.min(maxFrame, Math.max(Math.max(wantedFrame, diagnosticExposureNs), 3_000_000L)); b.set(CaptureRequest.SENSOR_FRAME_DURATION, frame);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); b.set(CaptureRequest.LENS_FOCUS_DISTANCE, getIntent().getFloatExtra("pairFocusDistance",0f)); b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f); b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF); b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        if (physicalSettings) {
            List<CaptureRequest.Key<?>> keys = manager.getCameraCharacteristics(logicalId).getAvailablePhysicalCameraRequestKeys();
            JSONArray applied = new JSONArray();
            if (keys != null) for (CaptureRequest.Key<?> key : keys) {
                Object value = b.get(key);
                if (value != null) {
                    setPhysicalRequestValue(b, key, value);
                    applied.put(key.getName());
                }
            }
            if (mode == Mode.MP50 && getIntent().getBooleanExtra("mp50MaximumResolution", false)) {
                b.setPhysicalCameraKey(CaptureRequest.SENSOR_PIXEL_MODE,
                        CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, physicalId);
                applied.put(CaptureRequest.SENSOR_PIXEL_MODE.getName());
            }
            record("physical_request_settings", json("physicalId", physicalId, "keys", applied));
        }
        // The warmup stream is binning preview; only the actual still requests mode 0.
        // A RAW-only warmup stays in mode 0, avoiding the preview ISP conversion.
        int requestedSensorMode = mode == Mode.MP50 ? (template == CameraDevice.TEMPLATE_STILL_CAPTURE
                || getIntent().getBooleanExtra("rawWarmup", false) ? 0 : 1) : 5;
        vendor(b, MODE_KEY, new int[]{requestedSensorMode});
        if (mode == Mode.MP50 && template == CameraDevice.TEMPLATE_STILL_CAPTURE
                && (bridgeProbe() || getIntent().getBooleanExtra("mp50FullsizeRoute", false)))
            vendor(b, "xiaomi.remosaic.enabled", new int[]{1});
        if (mode == Mode.MP50 && (bridgeProbe() || getIntent().getBooleanExtra("mp50MasterCallback", false)))
            vendor(b, "org.codeaurora.qcamera3.sessionParameters.EnableMCXMasterCb", new int[]{1});
        if (bridgeProbe() && getIntent().getBooleanExtra("bridgeXcfa", false))
            vendor(b, "org.codeaurora.qcamera3.sessionParameters.EnableXCFAOptimization", byte[].class, new byte[]{1});
        if (requestedSensorHint || getIntent().hasExtra("sensorHintModes")) {
            String modes = getIntent().getStringExtra("sensorHintModes");
            String[] entries = modes == null ? new String[]{Integer.toString(requestedSensorMode)} : modes.split(",", -1);
            if (entries.length < 1 || entries.length > 3) throw new IllegalArgumentException("sensor hint count outside audited camera table");
            ByteBuffer packed = ByteBuffer.allocate(4 * entries.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (String entry : entries) {
                int value = Integer.parseInt(entry);
                if (value < -1 || value > 31) throw new IllegalArgumentException("sensor hint index outside audited table");
                packed.putInt(value);
            }
            byte[] hint = packed.array();
            vendor(b, "org.quic.camera.customsensormode.CustomizedSensorModeInfo", byte[].class, hint);
        }
        if (mode == Mode.LOFIC) {
            if (getIntent().getBooleanExtra("loficPhotoModule", false)) {
                if (!getIntent().getBooleanExtra("previewOnly", false))
                    throw new IllegalArgumentException("Photo module diagnostic is preview-only");
                vendor(b, "xiaomi.app.module", new int[]{163});
            }
            vendor(b, HdrMode.LOFIC, new int[]{1});
            vendor(b, HdrMode.DCG, new int[]{getIntent().getIntExtra("loficDcgWord", HdrMode.pack(5, 14, 10))});
            vendor(b, HdrMode.EXPOSURES, new int[]{getIntent().getIntExtra("loficExposureCount", 2)});
            if (getIntent().getBooleanExtra("perFrameHdrControls", false)) {
                vendor(b, "com.qti.stats_control.DCGMode", new int[]{5});
                vendor(b, "com.qti.stats_control.ExposureCount", new int[]{2});
            }
        }
        else {
            vendor(b, HdrMode.LOFIC, new int[]{0}); vendor(b, HdrMode.DCG, new int[]{0}); vendor(b, HdrMode.EXPOSURES, new int[]{1});
        }
            if ((mode == Mode.MP50 ? requestedMp50SessionType : getIntent().getIntExtra("loficSessionType", 0)) != SessionConfiguration.SESSION_REGULAR) {
            vendor(b, SESSION_CLIENT_NAME, byte[].class, ((getIntent().getBooleanExtra("ownClientName", false)
                    ? getPackageName() : "com.android.camera") + "\0").getBytes(StandardCharsets.UTF_8));
            vendor(b, SESSION_PROCESS_ID, new int[]{android.os.Process.myPid()});
            vendor(b, SESSION_OPERATION, new int[]{sessionOperation});
            vendor(b, SESSION_STREAM_USECASE, new int[]{2});
            vendor(b, SESSION_FUNCTION_MASK, new long[]{4L});
            vendor(b, SESSION_CLOUD_SWITCH, new long[]{120L});
            }
        return b;
    }
    private void vendor(CaptureRequest.Builder b, String name, int[] value) { vendor(b, name, int[].class, value); }
    private void vendor(CaptureRequest.Builder b, String name, long[] value) { vendor(b, name, long[].class, value); }

    /**
     * Xiaomi's vendor tags are exposed through the Camera2 vendor-tag
     * descriptor, but the HAL treats the session-parameter subset specially.
     * Reusing the registered session key is more reliable than constructing a
     * same-named key when a custom operation mode is being configured.
     */
    @SuppressWarnings("unchecked")
    private <T> CaptureRequest.Key<T> requestKey(String name, Class<T> type) {
        try {
            List<CaptureRequest.Key<?>> keys = sensor.getAvailableSessionKeys();
            if (keys != null) {
                for (CaptureRequest.Key<?> candidate : keys) {
                    if (name.equals(candidate.getName())) {
                        return (CaptureRequest.Key<T>) candidate;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            record("session_key_lookup_failed", json("name", name, "error", ignored.toString()));
        }
        return new CaptureRequest.Key<T>(name, type);
    }

    private <T> void vendor(CaptureRequest.Builder b, String name, Class<T> type, T value) {
        CaptureRequest.Key<T> key = requestKey(name, type);
        boolean registeredSessionKey = false;
        try {
            List<CaptureRequest.Key<?>> keys = sensor.getAvailableSessionKeys();
            if (keys != null) {
                for (CaptureRequest.Key<?> candidate : keys) {
                    if (name.equals(candidate.getName())) { registeredSessionKey = true; break; }
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            b.set(key, value);
            Object readback = b.get(key);
            record("vendor_key", json("name", name, "requestedType", type.getTypeName(),
                    "registeredSessionKey", registeredSessionKey,
                    "keyType", registeredSessionKey ? "registered-session-key" : type.getTypeName(),
                    "accepted", true, "value", vendorJson(value), "builderReadback", vendorJson(readback)));
        } catch (RuntimeException e) {
            record("vendor_key", json("name", name, "requestedType", type.getTypeName(),
                    "registeredSessionKey", registeredSessionKey,
                    "keyType", registeredSessionKey ? "registered-session-key" : type.getTypeName(),
                    "accepted", false, "error", e.toString()));
        }
    }
    private Object vendorJson(Object value) {
        if (value instanceof int[]) return ints((int[])value);
        if (value instanceof long[]) { JSONArray a = new JSONArray(); for (long v : (long[]) value) a.put(v); return a; }
        if (value instanceof byte[]) {
            byte[] b = (byte[])value;
            return json("utf8", new String(b, StandardCharsets.UTF_8).replace("\0", "\\0"), "hex", hex(b));
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private JSONObject requestSnapshot(CaptureRequest request, Mode mode, long generation) {
        JSONObject out = json("mode", mode.toString(), "generation", generation,
                "keyCount", request.getKeys().size());
        JSONObject values = new JSONObject();
        JSONArray names = new JSONArray();
        for (CaptureRequest.Key<?> key : request.getKeys()) {
            String name = key.getName();
            names.put(name);
            if (!(name.startsWith("com.xiaomi.sessionparams.")
                    || name.startsWith("org.codeaurora.qcamera3.sessionParameters.")
                    || name.equals(MODE_KEY)
                    || name.equals("android.sensor.sensitivity")
                    || name.equals("android.sensor.exposureTime")
                    || name.equals("android.sensor.frameDuration")
                    || name.equals("android.lens.focusDistance"))) continue;
            try {
                Object value = request.get((CaptureRequest.Key<Object>) key);
                values.put(name, vendorJson(value));
            } catch (Exception e) {
                try { values.put(name, "unreadable: " + e); } catch (Exception ignored) { }
            }
        }
        try { out.put("keys", names); out.put("selectedValues", values); }
        catch (Exception ignored) { }
        return out;
    }

    private void rejectFrame(PendingFrame frame, String reason) {
        if (frame == null || frame.lifecycle.failed) return;
        frame.lifecycle.reject(); frame.terminal = true; chainAborted = true;
        record("frame_rejected", json("sequence", frame.sequence, "generation", frame.generation,
                "mode", frame.mode.toString(), "reason", reason));
        say("第 " + frame.sequence + " 张未通过完整性核验；已停止模式链，保留失败证据");
    }

    private void verifyFrame(PendingFrame frame) {
        if (destroyed || frame.lifecycle.failed || frame.lifecycle.validated
                || frame.result == null || frame.ready == null || frame.startedTimestamp == 0) return;
        JSONObject r = frame.result, v = frame.ready;
        if (r.optInt("actualSensorMode", -1) != (frame.mode == Mode.MP50 ? 0 : 5)) {
            rejectFrame(frame, "actual_sensor_mode_mismatch"); return;
        }
        boolean matches = captureMetadataMatches(r, frame.startedTimestamp, v.optLong("timestampNs", -1));
        boolean dimensions = v.optInt("width") == frame.size.getWidth()
                && v.optInt("height") == frame.size.getHeight();
        if (!matches || !dimensions || !v.optBoolean("payloadComplete", false)) {
            rejectFrame(frame, !v.optBoolean("payloadComplete", false) ? "incomplete_raw_payload"
                    : "timestamp_metadata_dimensions_or_payload_mismatch"); return;
        }
        if (!frame.lifecycle.ready) {
            if (!record("frame_ready", json("sequence", frame.sequence, "generation", frame.generation,
                    "timestampNs", frame.startedTimestamp, "persisted", false, "hardwareModeVerified", false))) {
                rejectFrame(frame, "ready_journal_failed"); return;
            }
            frame.lifecycle.markReady(true);
            if (frame.chain == null || frame.chain.length <= 2) continueChain(frame.chainIndex + 1, frame.chain);
        }
        if (frame.saved == null) return;
        boolean published = record("frame_validated", json("sequence", frame.sequence, "generation", frame.generation,
                "mode", frame.mode.toString(), "timestampNs", v.optLong("timestampNs"),
                "sha256", frame.saved.optString("sha256"), "hardwareModeVerified", false,
                "scope", "transport_payload_and_capture_metadata_only"));
        if (!published) { rejectFrame(frame, "validation_journal_failed"); return; }
        frame.lifecycle.markPersisted(); frame.lifecycle.validate(); frame.terminal = true;
        // Existing longer diagnostic sequences stay serialized at persistence.
        if (frame.chain != null && frame.chain.length > 2) continueChain(frame.chainIndex + 1, frame.chain);
        say("第 " + frame.sequence + " 张完整性核验通过；硬件模式仍须独立验证");
        if (!chainAborted && !pairReported && batchFrames.size() == 2 && frame.chain != null && frame.chain.length == 2
                && batchFrames.stream().allMatch(f -> f.lifecycle.validated)) {
            PendingFrame first = batchFrames.get(0), second = batchFrames.get(1);
            if (second.startedTimestamp <= first.startedTimestamp) {
                rejectFrame(second, "nonincreasing_pair_timestamp"); return;
            }
            double[] gaps = FastHybridFrameLifecycle.intervals(first.startedTimestamp,
                    first.result.optLong("effectiveExposureNs"), second.startedTimestamp, second.result.optLong("effectiveExposureNs"));
            pairReported = record("pair_transport_validated", json("startIntervalMs", gaps[0],
                    "centerIntervalMs", gaps[1], "firstRowDeadTimeMs", gaps[2],
                    "hardwareModeVerified", false, "scope", "first_sensor_row; rolling_shutter_skew_not_included"));
            if (!pairReported) rejectFrame(second, "pair_journal_failed");
        }
    }

    private void shoot(Mode mode, Size size, int index, Mode[] chain) {
        if (pendingFrame != null && !pendingFrame.terminal && !pendingFrame.lifecycle.canHandoff()) {
            record("capture_blocked", "previous_capture_pending"); return;
        }
        final long generation = activeSessionGeneration;
        final CameraCaptureSession captureSession = session;
        if (index == 0) {
            if (batchFrames.stream().anyMatch(f -> !f.lifecycle.finished() || f.ioPending)) return;
            batchFrames.clear(); chainAborted = false; pairReported = false;
        }
        if (chainAborted || batchFrames.stream().filter(f -> f.ioPending).count() >= 2) {
            record("capture_blocked", "aborted_or_two_frame_capacity"); return;
        }
        final PendingFrame frame = new PendingFrame(++sequence, generation, mode, size, index, chain);
        batchFrames.add(frame);
        pendingFrame = frame;
        try {
            verifyFactoryInfinity();
            CaptureRequest.Builder b = request(mode);
            if (getIntent().getBooleanExtra("rawPreviewIntent", false))
                b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
            if (!(mode == Mode.LOFIC && getIntent().getBooleanExtra("loficNoMainRaw", false)))
                b.addTarget(rawReader.getSurface());
            if (!getIntent().getBooleanExtra("mp50PhysicalRaw", false)) {
                if (previewSurface != null) b.addTarget(previewSurface);
                if (downscaleSurface != null && getIntent().getBooleanExtra("snapshotDownscale", true)) b.addTarget(downscaleSurface);
            }
            b.setTag(frame.sequence);
            if ((mode == Mode.MP50 ? requestedMp50SessionType : getIntent().getIntExtra("loficSessionType", 0)) != SessionConfiguration.SESSION_REGULAR) {
                String imageName = "JC_FAST_" + frame.sequence + "_" + System.nanoTime();
                b.set(new CaptureRequest.Key<byte[]>("xiaomi.snapshot.imageName", byte[].class),
                        (imageName + "\0").getBytes(StandardCharsets.UTF_8));
                record("snapshot_image_name", imageName);
            }
            final long start = System.nanoTime();
            CaptureRequest captureRequest = b.build();
            if (!record("capture_request_built", requestSnapshot(captureRequest, mode, generation).put("sequence", frame.sequence)))
                throw new IOException("capture journal unavailable; shutter not issued");
            captureSession.capture(captureRequest, new CameraCaptureSession.CaptureCallback() {
                public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r, long timestamp, long number) {
                    if (!isActive(generation, s) || frame != pendingFrame || frame.terminal) return;
                    frame.startedTimestamp = timestamp;
                    record("capture_started", json("sequence", frame.sequence, "generation", generation,
                            "timestampNs", timestamp, "frameNumber", number));
                    verifyFrame(frame);
                }
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    if (!isActive(generation, s) || frame != pendingFrame) return;
                    try {
                        JSONObject j = resultJson(result, mode, size, frame.sequence, start).put("generation", generation);
                        record("capture_result", j); writeMeta(frame.sequence, j);
                        frame.result = j; verifyFrame(frame);
                    } catch (Exception e) { fail("metadata", e); rejectFrame(frame, "metadata_error"); }
                }
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                    record("capture_failed", json("sequence", frame.sequence, "generation", generation,
                            "reason", f.getReason(), "mode", mode.toString()));
                    rejectFrame(frame, "capture_failed");
                }
            }, worker);
            worker.postDelayed(() -> {
                if (!frame.lifecycle.ready && !frame.terminal) rejectFrame(frame, "result_image_join_timeout");
            }, 15000L);
            say("已发出第 " + frame.sequence + " 张 " + mode + " 请求；等待完整输出核验");
        } catch (Exception e) { fail("capture_" + mode, e); rejectFrame(frame, "capture_exception"); }
    }

    private boolean isActive(long generation, CameraCaptureSession candidate) { return generation == activeSessionGeneration && candidate == session && !destroyed; }

    private boolean captureMetadataMatches(JSONObject r, long started, long image) {
        if(getIntent().hasExtra("pairFocusDistance")&&!r.optBoolean("focusVerified"))return false;
        long timestamp = r.optLong("timestampNs", -2);
        int iso = r.optInt("iso", -1); long exposure = r.optLong("exposureNs", -1);
        double focus = r.optDouble("focusDistance", Double.NaN);
        boolean physical = r.optBoolean("physicalResultPresent", false);
        boolean infinity = r.optBoolean("focusVerified",r.optBoolean("factoryInfinityVerified", false));
        return FastHybridFrameCheck.metadataMatches(started, image, timestamp, iso, exposure, focus, physical, infinity,
                r.optBoolean("mode0UnityIso70Verified", false))
                || FastHybridSensorMetadata.matches(started, image, timestamp, iso, exposure, focus, physical, infinity,
                        r.optBoolean("driverMetadataVerified", false));
    }

    private JSONObject resultJson(TotalCaptureResult r, Mode mode, Size size, int seq, long start) throws Exception {
        CaptureResult actual = r;
        if (physicalId != null) {
            Map<String, CaptureResult> physical = r.getPhysicalCameraResults();
            CaptureResult candidate = physical == null ? null : physical.get(physicalId);
            if (candidate != null) actual = candidate;
        }
        JSONObject j = new JSONObject(); j.put("sequence", seq).put("requestedMode", mode.toString()).put("requestedSize", size.toString()).put("requestElapsedMs", (System.nanoTime() - start) / 1e6);
        j.put("factoryInfinityVerified", focusEvidence != null && !getIntent().hasExtra("pairFocusDistance"))
                .put("focusVerified",focusEvidence!=null).put("focusEvidence", focusEvidence);
        j.put("timestampSource", sensor.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));
        j.put("rollingShutterSkewNs", actual.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW));
        Integer actualIso = actual.get(CaptureResult.SENSOR_SENSITIVITY); Long actualExposure = actual.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        try {
            float[] aec = actual.get(new CaptureResult.Key<float[]>("org.quic.camera2.statsconfigs.AECLinearGain", float[].class));
            float[] postGain = actual.get(new CaptureResult.Key<float[]>("org.quic.camera2.properties_sensor.PostSensorGainId", float[].class));
            byte[] modeBytes = actual.get(new CaptureResult.Key<byte[]>("org.quic.camera2.properties_sensor.SensorCurrentMode", byte[].class));
            int sensorMode = modeBytes == null || modeBytes.length != 1 ? -1 : modeBytes[0] & 255;
            if (sensorMode == -1) {
                int[] current = actual.get(new CaptureResult.Key<int[]>("org.codeaurora.qcamera3.sensor_meta_data.current_mode", int[].class));
                if (current != null && current.length == 1) sensorMode = current[0];
            }
            Integer boost = actual.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
            boolean unity = getIntent().getBooleanExtra("allowMp50UnityIso70", false) && mode == Mode.MP50
                    && FastHybridFrameCheck.unityMode0(actualIso == null ? -1 : actualIso, sensorMode, aec, postGain, boost == null ? -1 : boost);
            j.put("aecLinearGain", JSONObject.wrap(aec)).put("postSensorGain", JSONObject.wrap(postGain))
                    .put("actualSensorMode", sensorMode).put("postRawBoost", boost).put("mode0UnityIso70Verified", unity);
        } catch (RuntimeException e) { j.put("gainMetadataError", e.toString()).put("mode0UnityIso70Verified", false); }
        j.put("timestampNs", actual.get(CaptureResult.SENSOR_TIMESTAMP)); j.put("requestedIso", 50); j.put("requestedExposureNs", reverseHdrSent?pairHdrExposure:getIntent().getLongExtra("sensorExposureNs", 1_000_000L)); j.put("iso", actualIso); j.put("exposureNs", actualExposure); j.put("sensorTimingMetadataValid", actualIso != null && actualIso > 0 && actualExposure != null && actualExposure > 0); j.put("frameDurationNs", actual.get(CaptureResult.SENSOR_FRAME_DURATION));
        j.put("effectiveExposureNs", actualExposure).put("exposureAuthority", "standard_capture_result");
        try {
            byte[] bytes = actual.get(new CaptureResult.Key<byte[]>(FastHybridSensorMetadata.TAG, byte[].class));
            if (bytes != null) {
                FastHybridSensorMetadata applied = new FastHybridSensorMetadata(bytes);
                JSONObject decoded = json("tag", FastHybridSensorMetadata.TAG, "order", "long,middle,short",
                        "analogGain", JSONObject.wrap(applied.analog), "digitalGain", JSONObject.wrap(applied.digital),
                        "ispGain", JSONObject.wrap(applied.isp), "exposureNs", JSONObject.wrap(applied.exposureNs),
                        "cgRatio", applied.cgRatio, "loficRatio", applied.loficRatio);
                j.put("sensorApplied", decoded);
                if(single50()){
                    boolean minimum=mode==Mode.MP50&&j.optInt("actualSensorMode",-1)==0&&actualIso!=null&&actualIso==70
                        &&j.optInt("postRawBoost",-1)==100&&focusEvidence!=null&&focusEvidence.optBoolean("sensorLayoutVerified")
                        &&applied.analog[0]==1&&applied.digital[0]==1&&applied.isp[0]==1
                        &&applied.analog[1]==0&&applied.analog[2]==0&&applied.digital[1]==0&&applied.digital[2]==0&&applied.isp[1]==0&&applied.isp[2]==0;
                    j.put("native50MinimumGainVerified",minimum).put("native50GainAuthority",FastHybridSensorMetadata.TAG);
                }
                long branchTolerance=(manualPair()||classicHdr())?PairManualPolicy.roundingTolerance(applied.exposureNs[0]):tripodPair()?1:0;
                boolean verified = mode == Mode.LOFIC && getIntent().getBooleanExtra("appliedSensorMetadata", false)
                        && focusEvidence != null && focusEvidence.optBoolean("sensorLayoutVerified", false)
                        && applied.isEqualExposureUnityMode5(j.optInt("actualSensorMode", -1),
                                j.optInt("postRawBoost", -1), j.optLong("frameDurationNs", -1),branchTolerance);
                j.put("branchRoundingToleranceNs",branchTolerance);
                j.put("driverMetadataVerified", verified);
                if (verified) {
                    // Preserve standard ISO/exposure zeros; use actual driver data with explicit provenance.
                    j.put("effectiveExposureNs", applied.exposureNs[0]).put("exposureAuthority", FastHybridSensorMetadata.TAG);
                    j.put("unityBaseIsoCalibration", 50).put("standardMetadataUnchanged", true);
                }
            }
        } catch (RuntimeException e) { j.put("appliedMetadataError", e.toString()).put("driverMetadataVerified", false); }
        if(getIntent().hasExtra("pairFocusDistance")) {
            Float distance=actual.get(CaptureResult.LENS_FOCUS_DISTANCE);Integer lensState=actual.get(CaptureResult.LENS_STATE);
            float desired=getIntent().getFloatExtra("pairFocusDistance",0);
            j.put("requestedFocusDistance",desired).put("lensState",lensState).put("focusAuthority","locked_preview_distance_and_stable_actuator_command")
                    .put("focusVerified",focusEvidence!=null&&focusEvidence.optInt("stableActuatorDac",-1)>=0&&distance!=null
                    && PairFocusPolicy.matches(desired,distance));
        }
        if(classicHdr()){
            j.put("raw14BlackLevels",JSONObject.wrap(actual.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)));
            android.hardware.camera2.params.RggbChannelVector gains=actual.get(CaptureResult.COLOR_CORRECTION_GAINS);
            if(gains!=null){double g=(gains.getGreenEven()+gains.getGreenOdd())/2.0;double[] v={g/gains.getRed(),1,g/gains.getBlue()};JSONArray neutral=new JSONArray();boolean valid=true;for(double n:v){if(!Double.isFinite(n)||n<=0||n>1000){valid=false;break;}neutral.put(Math.max(1,Math.round(n*1000000))).put(1000000);}if(valid)j.put("asShotNeutral",neutral);}
        }
        j.put("focusDistance", actual.get(CaptureResult.LENS_FOCUS_DISTANCE)); j.put("physicalResultPresent", actual != r || physicalId == null); j.put("actual", captureKeys(actual)); j.put("verification", "RAW buffer and dimensions must be checked before mode is considered successful"); return j;
    }
    private JSONObject captureKeys(CaptureResult r) {
        JSONObject j = new JSONObject();
        for (CaptureResult.Key<?> k : r.getKeys()) {
            String n = k.getName(), lower = n.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("mode") || lower.contains("hdr") || lower.contains("lofic")
                    || lower.contains("remosaic") || lower.contains("exposure")
                    || lower.contains("gain") || lower.contains("sensitivity") || lower.contains("contexttype"))
                try { j.put(n, JSONObject.wrap(r.get(k))); } catch (Exception ignored) {}
        }
        return j;
    }
    private void verifyFactoryInfinity() throws Exception {
        boolean lockedFocus=getIntent().hasExtra("pairFocusDistance");
        float desiredFocus=getIntent().getFloatExtra("pairFocusDistance",0);
        if(!Float.isFinite(desiredFocus)||desiredFocus<0||desiredFocus>100)throw new IOException("invalid locked focus distance");
        String expectedSha = getIntent().getStringExtra("focusConfigSha");
        String expectedPid = getIntent().getStringExtra("focusProviderPid");
        if (expectedSha == null && expectedPid == null) return;
        if (expectedSha == null || !expectedSha.matches("[0-9a-f]{64}")
                || expectedPid == null || !expectedPid.matches("[0-9]+")
                || !FastHybridFocusEvidence.isMainRoute(logicalId, physicalId))
            throw new IOException("invalid factory focus verification context");
        String command = "getprop init.svc_debug_pid.vendor.camera-provider; sha256sum /odm/etc/camera/camxoverridesettings.txt; "
                + "grep -E '^(manualAf|lensPos)=' /odm/etc/camera/camxoverridesettings.txt; "
                + "logcat -d -b main -v epoch --pid=" + expectedPid
                + " -T '" + String.format(Locale.US,"%.3f",focusWindowStartMs/1000.0) + "'"
                + " -e 'Actuator library loaded|Infinity:|ManualAF Override|TargetPosition'";
        if (getIntent().getBooleanExtra("appliedSensorMetadata", false)&&!classicHdr())
            command += "; sha256sum /vendor/lib64/hw/camera.qcom.core.so /odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so";
        long auditStart=SystemClock.elapsedRealtime();
        String evidence;boolean readComplete;
        if(classicHdr()){
            if(classicFileAudit==null)throw new IOException("file audit absent");
            evidence=classicFileAudit.get(10,java.util.concurrent.TimeUnit.SECONDS);
            readComplete=ClassicDriverEvidence.valid(evidence,expectedPid,expectedSha);
            File live=new File(root,"focus-live.txt");long until=SystemClock.elapsedRealtime()+2000;String log;
            do{
                try(InputStream in=new FileInputStream(live)){log=new String(NativeRaw.bytes(in,4*1024*1024),java.nio.charset.StandardCharsets.UTF_8);}
                if(PairFocusPolicy.stableDac(log,focusWindowStartMs)>=0)break;
                if(SystemClock.elapsedRealtime()>=until)throw new IOException("fresh actuator log not ready; no shutter");
                Thread.sleep(25);
            }while(true);
            evidence+="\n"+log;
        }else{
            java.lang.Process process = RootProcess.start(command, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread drain = new Thread(() -> { try {
                byte[] block = new byte[8192]; int n;
                while ((n = process.getInputStream().read(block)) != -1) {
                    if (output.size() + n > 4 * 1024 * 1024) { process.destroy(); return; }
                    output.write(block, 0, n);
                }
            } catch (IOException ignored) { } });
            drain.start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy(); drain.join(1000);
                record("factory_focus_audit_timeout", json("providerPid", expectedPid,
                        "sinceWallMs", focusWindowStartMs, "timeoutSeconds", 10,
                        "evidence", output.toString("UTF-8")));
                throw new IOException("focus audit timed out; partial evidence preserved");
            }
            drain.join(1000);
            evidence = output.toString("UTF-8");
            readComplete=process.exitValue()==0&&!drain.isAlive();
        }
        boolean confirmed = readComplete
                && evidence.startsWith(expectedPid + "\n")
                && evidence.contains(expectedSha + "  /odm/etc/camera/camxoverridesettings.txt")
                && (lockedFocus ? evidence.contains("manualAf=0\n") && PairFocusPolicy.stableDac(evidence,focusWindowStartMs)>=0 : evidence.contains("manualAf=2\n") && evidence.contains("lensPos=824\n")
                && FastHybridFocusEvidence.matches(evidence, focusWindowStartMs));
        JSONObject audit = json("verified", confirmed, "providerPid", expectedPid, "configSha", expectedSha,
                "logicalId", logicalId, "physicalId", physicalId,
                "requestedFocusDistance", desiredFocus, "stableActuatorDac",lockedFocus?PairFocusPolicy.stableDac(evidence,focusWindowStartMs):-1, "sinceWallMs", focusWindowStartMs, "evidence", evidence,
                "auditElapsedMs", SystemClock.elapsedRealtime()-auditStart,"fileHashScope",getIntent().hasExtra("classicDriverProofSha")?"unchanged_driver_provider_generation":"fresh_per_capture",
                "scope", getIntent().hasExtra("pairFocusDistance") ? "locked preview AF distance and repeated identical actuator commands; not independent optical displacement" : "factory-calibrated actuator command; not independent physical displacement");
        audit.put("sensorLayoutVerified", confirmed
                && evidence.contains(FastHybridSensorMetadata.CORE_SHA + "  /vendor/lib64/hw/camera.qcom.core.so")
                && (single50()?evidence.contains("145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b  /odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so")
                :evidence.contains("3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233  /odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so")));
        if (!record("factory_focus_audit", audit) || !confirmed) {
            focusEvidence = null; throw new IOException("fresh factory infinity actuator evidence missing");
        }
        focusEvidence = audit;
    }

    private void writeMeta(int seq, JSONObject j) throws Exception {
        File destination = new File(root, String.format(Locale.US, "frame_%04d.json", seq));
        File temp = new File(root, destination.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(j.toString(2).getBytes("UTF-8")); }
        android.system.Os.rename(temp.getAbsolutePath(), destination.getAbsolutePath());
    }

    private void onImage(ImageReader reader, Mode mode, Size size, long generation) {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if (image == null) return;
            if (generation != activeSessionGeneration || reader != rawReader) {
                record("stale_raw_dropped", json("mode", mode.toString(), "generation", generation, "activeGeneration", activeSessionGeneration, "timestampNs", image.getTimestamp()));
                return;
            }
            final PendingFrame frame = pendingFrame;
            if (getIntent().getBooleanExtra("rawWarmup", false) && frame != null
                    && (frame.startedTimestamp == 0 || image.getTimestamp() != frame.startedTimestamp)) {
                record("warmup_raw_drained", json("timestampNs", image.getTimestamp())); return;
            }
            if (frame == null || frame.generation != generation || frame.mode != mode || frame.imageReceived) {
                record("unmatched_raw_dropped", json("generation", generation, "timestampNs", image.getTimestamp()));
                if (frame != null && frame.generation == generation) rejectFrame(frame, "unexpected_additional_image");
                return;
            }
            frame.imageReceived = true;
            final int seq = frame.sequence;
            final long timestamp = image.getTimestamp();
            final String base = String.format(Locale.US, "frame_%04d_%s_%dx%d_%d", seq,
                    mode.toString().toLowerCase(Locale.US), size.getWidth(), size.getHeight(), timestamp);
            Image.Plane p = image.getPlanes()[0];
            final long copyStartNs = System.nanoTime();
            ByteBuffer buf = p.getBuffer();
            final byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            final JSONObject received = new JSONObject()
                    .put("sequence", seq).put("mode", mode.toString()).put("generation", generation)
                    .put("copyElapsedMs", (System.nanoTime() - copyStartNs) / 1e6)
                    .put("width", image.getWidth()).put("height", image.getHeight())
                    .put("format", image.getFormat()).put("timestampNs", timestamp)
                    .put("rowStride", p.getRowStride()).put("pixelStride", p.getPixelStride())
                    .put("bytes", bytes.length).put("raw", base + ".raw");

            // Arrival alone cannot trigger the next exposure. Preserve and
            // validate the copied payload, then join it with capture metadata.
            record("raw_received", received);
            final File raw = new File(root, base + ".raw");
            frame.ioPending = true;
            inspector.execute(() -> {
                try {
                    long scanStart = System.nanoTime();
                    FastHybridFrameCheck integrity = new FastHybridFrameCheck(bytes,
                            received.getInt("width"), received.getInt("height"),
                            received.getInt("rowStride"), received.getInt("pixelStride"), received.getInt("format"), false);
                    final JSONObject ready = new JSONObject(received.toString())
                            .put("scanElapsedMs", (System.nanoTime() - scanStart) / 1e6)
                            .put("layoutValid", integrity.layoutValid).put("payloadComplete", integrity.complete)
                            .put("nonzeroFraction", integrity.nonzeroFraction).put("nonzeroRows", integrity.nonzeroRows)
                            .put("extremaCollected", false);
                    worker.post(() -> { frame.ready = ready; verifyFrame(frame); });
                    // The copied array owns the payload across session reconfiguration.
                    writer.execute(() -> {
                        try {
                            long saveStart = System.nanoTime();
                            FastHybridFrameCheck persistedCheck = new FastHybridFrameCheck(bytes,
                                    ready.getInt("width"), ready.getInt("height"), ready.getInt("rowStride"),
                                    ready.getInt("pixelStride"), ready.getInt("format"));
                            if (persistedCheck.nonzeroSamples != integrity.nonzeroSamples
                                    || persistedCheck.nonzeroRows != integrity.nonzeroRows)
                                throw new IOException("payload changed after handoff");
                            String digest = sha256(bytes);
                            try (FileOutputStream out = new FileOutputStream(raw)) { out.write(bytes); }
                            JSONObject saved = new JSONObject(ready.toString()).put("sha256", digest)
                                    .put("minimum", persistedCheck.minimum).put("maximum", persistedCheck.maximum)
                                    .put("extremaCollected", true)
                                    .put("saveElapsedMs", (System.nanoTime() - saveStart) / 1e6);
                            File sidecar = new File(root, base + ".json");
                            File temp = new File(root, base + ".json.tmp");
                            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(saved.toString(2).getBytes("UTF-8")); }
                            android.system.Os.rename(temp.getAbsolutePath(), sidecar.getAbsolutePath());
                            if (!record("raw_saved", saved)) throw new IOException("raw journal failed");
                            // Finalization belongs to the frame, even after its generation ends.
                            worker.post(() -> { frame.ioPending = false; frame.saved = saved; verifyFrame(frame); });
                        } catch (Exception e) {
                            record("raw_save_failed", json("sequence", seq, "error", e.toString()));
                            worker.post(() -> { frame.ioPending = false; rejectFrame(frame, "raw_save_failed"); });
                        }
                    });
                } catch (Exception e) {
                    record("raw_inspection_failed", json("sequence", seq, "error", e.toString()));
                    worker.post(() -> { frame.ioPending = false; rejectFrame(frame, "raw_inspection_failed"); });
                }
            });
        } catch (Exception e) {
            fail("raw", e);
            rejectFrame(pendingFrame, "raw_read_error");
        } finally {
            if (image != null) image.close();
        }
    }
    private String sha256(byte[] b) throws Exception { MessageDigest d = MessageDigest.getInstance("SHA-256"); return hex(d.digest(b)); }
    private String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.US, "%02x", x & 255)); return s.toString(); }

    private void continueChain(int index, Mode[] chain) { if (chainAborted || chain == null || index >= chain.length) return; worker.post(() -> { if (!chainAborted && !destroyed) configure(chain[index], true, index, chain); }); }
    private void closeReaders(Collection<ImageReader> values) { for (ImageReader r : values) try { r.close(); } catch (Exception ignored) {} }
    private void closeSessionOnly() { for (PendingFrame f : batchFrames) if (!f.terminal) rejectFrame(f, "session_closed"); pendingFrame = null; ++nextSessionGeneration; activeSessionGeneration = 0; configuring = false; pendingAutoShoot = false; if (session != null) { session.close(); session = null; } closeReaders(readers); readers.clear(); rawReader = null; if (previewSurface != null) previewSurface.release(); if (previewTexture != null) previewTexture.release(); if (downscaleSurface != null) downscaleSurface.release(); if (downscaleTexture != null) downscaleTexture.release(); previewSurface = null; previewTexture = null; downscaleSurface = null; downscaleTexture = null; configuredMode = null; configuredSize = null; configuredFormat = -1; }
    private void closeCamera() { if (bridgePreparedTransition != null) { bridgePreparedTransition.close(); bridgePreparedTransition = null; } closeSessionOnly(); if (camera != null) { camera.close(); camera = null; } say("已停止并关闭 CameraDevice；失败和成功记录均保留"); }
    private JSONArray ints(int[] values) { JSONArray a = new JSONArray(); for (int v : values) a.put(v); return a; }
    private JSONObject json(Object... pairs) { JSONObject j = new JSONObject(); try { for (int i = 0; i + 1 < pairs.length; i += 2) j.put(String.valueOf(pairs[i]), pairs[i + 1]); } catch (Exception ignored) {} return j; }
    private void fail(String where, Exception e) { if(single50()&&!single50Finalized){single50Finalized=true;closeCamera();try{PairCaptureStore.atomic(new File(root,"failed.json"),json("where",where,"error",e.toString()));}catch(Exception ignored){}} if (bridgeProbe()) bridgeSent = true; Log.e(TAG, where, e); record("error", json("where", where, "error", e.toString())); say(where + " 失败：" + e.getMessage()); }
    @Override protected void onPause(){super.onPause();if(single50()&&!single50Finalized){try{PairCaptureStore.atomic(new File(root.getParentFile(),"cancel.json"),json("cancelled",true));}catch(Exception ignored){}if(!single50ShutterIssued)worker.post(()->fail("cancelled_before_shutter",new IOException("countdown left before first exposure")));}}
    @Override protected void onDestroy() { destroyed = true; if (worker != null) worker.post(() -> { closeCamera(); inspector.execute(() -> writer.shutdown()); inspector.shutdown(); thread.quitSafely(); }); super.onDestroy(); }
}
