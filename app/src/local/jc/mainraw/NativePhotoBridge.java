package local.jc.mainraw;

import android.content.*;
import local.jc.mainraw.NativeCaptureIndex.*;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Root is used only for the scoped, hash-locked native capture bridge. */
public final class NativePhotoBridge {
    public static final java.util.concurrent.atomic.AtomicBoolean WORKING=new java.util.concurrent.atomic.AtomicBoolean();
    private final Context context;
    private final File assets,cache;
    private final SharedPreferences prefs;
    public interface Progress {void say(String message);}
    public NativePhotoBridge(Context c)throws IOException {
        context=c;assets=new File(c.getFilesDir(),"native_bridge");cache=new File(c.getCacheDir(),"native_bridge");
        if(!assets.isDirectory()&&!assets.mkdirs())throw new IOException("Cannot create bridge directory");
        if(!cache.isDirectory()&&!cache.mkdirs())throw new IOException("Cannot create RAW cache");
        prefs=c.getSharedPreferences("native_bridge",0);
        for(String name:new String[]{"native_capture.sh","recipe.json","focus_infinity.txt","lofic_mode5_unity.so","com.xiaomi.plugin.offcamformatconvertor.so","com.xiaomi.plugin.offlineformatconvertor.so"}){
            try(InputStream in=c.getAssets().open(name);OutputStream out=new FileOutputStream(new File(assets,name))){copy(in,out);}
        }
    }
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static void copy(InputStream in,OutputStream out)throws IOException {byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    private String command(String cmd,int timeout)throws Exception {
        Process p=RootProcess.start(cmd,true);
        ByteArrayOutputStream output=new ByteArrayOutputStream();Thread reader=new Thread(()->{try{copy(p.getInputStream(),output);}catch(IOException ignored){}});reader.start();
        if(!p.waitFor(timeout,TimeUnit.SECONDS)){p.destroy();reader.join(2000);throw new IOException("Root 操作超时；保留会话编号，可重试恢复。");}
        reader.join(2000);String text=output.toString("UTF-8");if(p.exitValue()!=0)throw new IOException(failureMessage(text));return text;
    }
    private static String failureMessage(String text) {
        if(text.contains("PHOTO_BASE_INACTIVE"))return "相机实验基础补丁已撤回，尚未拍摄。需检查保护恢复记录后重新启用。";
        if(text.contains("Wrong native camera route"))return "原生相机打开了错误的主摄路径，已阻止快门；请恢复后重新准备。";
        if(text.contains("NO_SHUTTER_SETTINGS_CHANGED"))return "快门前发现采集会话或固定参数已改变，已取消拍摄。";
        if(text.contains("NO_SHUTTER_POLICY_BLOCKED")) {
            if(text.contains("CONTROLLER_LOG_TIMEOUT"))return "系统策略日志读取超时，已阻止快门。请恢复后重新准备。";
            if(text.contains("CONTROLLER_BLOCKED"))return "系统算法控制器暂时禁用了所需 HDR / SE。已关闭相机，未按快门；等待系统恢复后再拍。";
            if(text.contains("CONTROLLER_UNKNOWN_OR_STALE")||text.contains("CONTROLLER_INCOMPLETE")||text.contains("CONTROLLER_UPDATE_PENDING")||text.contains("CONTROLLER_NOT_REGISTERED"))return "尚未取得完整、及时的系统算法许可，已阻止快门。恢复后可重新准备。";
            return "当前预览没有进入单帧双路 RAW 模式，已阻止快门。恢复后可重新准备。";
        }
        return "Root 操作失败："+text;
    }
    private String helper(String action,String id){return "sh "+quote(new File(assets,"native_capture.sh").getAbsolutePath())+" "+action+" "+quote(id);}
    public String session(){return prefs.getString("session",null);}
    private void rootPreflight()throws Exception {
        String uid=command("id -u",20).trim();if(!uid.equals("0"))throw new IOException("su 已启动，但未取得 UID 0："+uid);
    }
    private boolean clearUnpreparedSession()throws Exception {
        String id=session();if(id==null)return false;
        String state=command(helper("status",id),20).trim();
        if(state.equals("NOT_PREPARED")){if(!prefs.edit().remove("session").commit())throw new IOException("不能清除未启动的会话记录");return true;}
        return false;
    }
    public String checkRoot()throws Exception {
        rootPreflight();boolean cleared=clearUnpreparedSession();
        return "Root 检查通过：实际 UID 0。\n调用方式："+RootProcess.route()+
            (cleared?"\n已清除从未启动的旧会话；可直接准备 ISO50 拍摄。":session()==null?"\n没有待处理会话，可准备 ISO50 拍摄。":"\n已有真实会话，请先导出或恢复。")+"\n本次检查没有启动相机或改变 ISO。";
    }
    public void prepare(double exposureMs,boolean infinityFocus)throws Exception {
        prepare(exposureMs,infinityFocus,context.getSharedPreferences("capture_ui",0).getBoolean("highlightUnity",true));
    }
    public void prepare(double exposureMs,boolean infinityFocus,boolean unity)throws Exception {
        rootPreflight();clearUnpreparedSession();
        if(session()!=null)throw new IOException("请先导出或恢复上一会话。");
        if(context.getFilesDir().getUsableSpace()<1_500_000_000L)throw new IOException("原生采集与多 DNG 导出至少需要 1.5 GB 可用空间。");
        if(!Double.isFinite(exposureMs)||exposureMs<0.023821||exposureMs>1000)throw new IOException("原生实验曝光须为 0.023821–1000 ms，ISO 固定 50。");
        long ns=Math.round(exposureMs*1e6);
        if(ns>=30681&&ns<1_000_000_000L)ns=Math.min(1_000_000_000L,(long)Math.ceil(Math.ceil((ns-1)/NativeEttr.LINE_NS)*NativeEttr.LINE_NS));
        long shortNs=Math.round(ns/12.61943244934082);
        String id="s"+System.currentTimeMillis();if(!prefs.edit().putString("session",id).putBoolean("sessionInfinity",infinityFocus).putBoolean("sessionUnity",unity).remove("ettr").commit())throw new IOException("无法保存采集恢复记录；尚未启动采集");
        try{
            String result=command(helper("prepare",id)+" "+ns+" "+shortNs+" "+(infinityFocus?1:0)+" "+(unity?1:0),60);
            if(!result.contains("READY_ISO50"))throw new IOException("原生 ISO50 采集尚未准备完成");
        }catch(RootProcess.LaunchFailure e){prefs.edit().remove("session").commit();throw e;}
    }
    public void captureAndWait()throws Exception {
        String id=session();if(id==null)throw new IOException("没有采集会话");
        if(!command("timeout -k 2 140 "+helper("capture",id),150).contains("CAPTURE_READY"))throw new IOException("未收到完整配对数据");
    }
    public boolean verifiedPolicyBlockedBeforeShutter() {
        String id=session();if(id==null)return false;
        try{return command(helper("retry_state",id),20).trim().equals("VERIFIED_NO_SHUTTER_POLICY_BLOCKED");}
        catch(Exception e){return false;}
    }
    public void showApp()throws Exception {command("am start -n local.jc.mainraw/.NativePhotoActivity",15);}
    public void recover()throws Exception {
        String id=session();if(id==null)return;
        String state=command(helper("status",id),20).trim();
        if(!state.equals("NOT_PREPARED"))command(helper("restore",id),60);
        clearJournal();prefs.edit().remove("session").commit();
    }
    public JSONObject meterFirstShot()throws Exception {
        String id=session();if(id==null)throw new IOException("没有测光样张");
        ArrayList<Pair> pairs=NativeCaptureIndex.decode(command(helper("finish",id),90).trim());
        if(pairs.size()!=1)throw new IOException("测光必须只有一组 RAW");
        Pair p=pairs.get(0);p.meta=metadata(p.ma);
        NativeRaw.pair(p.meta,metadata(p.mb));NativeRaw.pair(p.meta,metadata(p.pa));NativeRaw.pair(p.meta,metadata(p.pb));
        File raw=fetch(p.b);NativeEttr.Result result;
        try{result=NativeEttr.measure(raw,p.meta.exposure,prefs.getBoolean("sessionUnity",false));}finally{raw.delete();}
        JSONObject report=new JSONObject().put("method","LOFIC_top10_mean_two_shots")
            .put("meterSession",id).put("meterFrame",p.frame).put("meterTimestampNs",p.meta.timestamp)
            .put("meterRaw10Sha256",p.b.hash).put("firstExposureNs",p.meta.exposure)
            .put("top10Mean",result.mean).put("blackLevel",NativeEttr.BLACK).put("targetWhite",NativeEttr.WHITE)
            .put("multiplier",Double.isFinite(result.multiplier)?result.multiplier:JSONObject.NULL)
            .put("secondRequestedExposureNs",result.exposureNs).put("limitedToShutterRange",result.limited)
            .put("firstTop10Clipped",result.mean==1023).put("mode5LinePeriodNs",NativeEttr.LINE_NS).put("lineAlignment","round_up_to_whole_lines").put("iterations",1).put("exposureErrorRecheck",false)
            .put("loficUnity",prefs.getBoolean("sessionUnity",false)).put("unityTimingOffsetLines",prefs.getBoolean("sessionUnity",false)?0.76955:0).put("timingOffsetSource","stock_driver_model_not_independent_optical_measurement");
        // First shot is retained in the native dump but not mixed into the final DNG.
        recover();return report;
    }
    public void setEttrReport(JSONObject report)throws IOException {
        if(!prefs.edit().putString("ettr",report.toString()).commit())throw new IOException("不能保存两张测光记录");
    }
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.US,"%02x",v&255));return s.toString();}
    private File fetch(Entry e)throws Exception {
        File file=new File(cache,e.name);Process p=RootProcess.start("cat "+quote("/data/vendor/camera/"+e.name),false);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long length=0;
        try(InputStream in=p.getInputStream();OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){length+=n;if(length>e.length)throw new IOException("Source length changed");digest.update(b,0,n);out.write(b,0,n);}}
        finally {if(p.isAlive()&&!p.waitFor(10,TimeUnit.SECONDS))p.destroy();}
        if(p.isAlive()||p.exitValue()!=0||length!=e.length||!hex(digest.digest()).equals(e.hash))throw new IOException("RAW source hash/length changed");return file;
    }
    private NativeRaw.Metadata metadata(Entry e)throws Exception {File f=fetch(e);try(InputStream in=new FileInputStream(f)){return NativeRaw.metadata(NativeRaw.bytes(in,4_000_000));}finally{f.delete();}}
    private Uri output(String folder,String name,String mime)throws IOException {
        ContentValues c=new ContentValues();c.put(MediaStore.MediaColumns.DISPLAY_NAME,name);c.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/JCCamera/"+folder);c.put(MediaStore.MediaColumns.MIME_TYPE,mime);c.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri u=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c);if(u==null)throw new IOException("Cannot create output");
        Set<String> journal=new HashSet<>(prefs.getStringSet("pending",Collections.emptySet()));journal.add(u.toString());
        if(!prefs.edit().putStringSet("pending",journal).commit()){context.getContentResolver().delete(u,null,null);throw new IOException("Cannot journal output");}return u;
    }
    private void clearJournal()throws IOException {for(String value:new HashSet<>(prefs.getStringSet("pending",Collections.emptySet())))try{context.getContentResolver().delete(Uri.parse(value),null,null);}catch(Exception e){throw new IOException("Cannot clean interrupted batch",e);}prefs.edit().remove("pending").commit();}
    public long lastExportExposureNs;
    public JSONObject latestPtcCheckpoint()throws Exception {
        return latestPtcCheckpoint(context.getSharedPreferences("capture_ui",0).getBoolean("highlightUnity",true));
    }
    public JSONObject latestPtcCheckpoint(boolean unity)throws Exception {
        String latest="";JSONObject completed=new JSONObject();boolean batchUnity=false,mixedGain=false;
        String[] projection={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME};
        try(android.database.Cursor cursor=context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,projection,
                MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ? AND "+MediaStore.MediaColumns.DISPLAY_NAME+" LIKE ? AND "+MediaStore.MediaColumns.IS_PENDING+"=0",
                new String[]{"Download/JCCamera/PTC_ISO50_%","%_metadata.json"},null)){
            if(cursor!=null)while(cursor.moveToNext()){
                Uri u=ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cursor.getLong(0));
                JSONObject report;try(InputStream in=context.getContentResolver().openInputStream(u)){report=new JSONObject(new String(NativeRaw.bytes(in,1_000_000),"UTF-8"));}
                JSONObject tag=report.optJSONObject("ptc");if(tag==null)continue;String b=tag.getString("batch");
                if(!b.matches("PTC_ISO50_[0-9]+"))continue;
                if(b.compareTo(latest)>0){latest=b;completed=new JSONObject();batchUnity=report.optBoolean("loficUnity",false);mixedGain=false;}
                if(b.equals(latest)){mixedGain|=batchUnity!=report.optBoolean("loficUnity",false);completed.put("L"+String.format(Locale.US,"%03d",tag.getInt("level"))+"_"+tag.getString("shot"),report.getLong("exposureNs"));}
            }
        }
        if(latest.isEmpty())throw new IOException("没有可继续的完整PTC批次");
        if(mixedGain||batchUnity!=unity)throw new IOException("旧PTC批次的LOFIC增益与当前开关不同；请切回对应增益或开始新批次");
        return new JSONObject().put("batch",latest).put("completed",completed);
    }
    public int export(Progress progress)throws Exception {return exportInternal(progress,null);}
    public int exportPtc(Progress progress,JSONObject tag)throws Exception {return exportInternal(progress,tag);}
    private int exportInternal(Progress progress,JSONObject ptc)throws Exception {
        String id=session();if(id==null)throw new IOException("没有待导出会话。");
        clearJournal();
        progress.say("关闭原生采集并恢复临时设置；检查所有样张是否确为 ISO 50…");
        String text=command(helper("finish",id),90).trim();if(text.isEmpty())throw new IOException("尚无新样张。请恢复会话后重新准备拍摄。");
        JSONObject lifecycle=new JSONObject(command(helper("evidence",id),20).trim());
        ArrayList<Pair> pairs=NativeCaptureIndex.decode(text);
        if(pairs.size()!=1)throw new IOException("单组模式实际返回 "+pairs.size()+" 组，拒绝输出。");
        for(Pair p:pairs){p.meta=metadata(p.ma);NativeRaw.pair(p.meta,metadata(p.mb));NativeRaw.pair(p.meta,metadata(p.pa));NativeRaw.pair(p.meta,metadata(p.pb));}
        if(ptc!=null){long actual=pairs.get(0).meta.exposure,expected=ptc.getLong("pairExpectedExposureNs"),requested=ptc.getLong("requestedExposureNs");
            if(expected>0&&actual!=expected)throw new IOException("PTC A/B实际曝光不一致："+expected+" / "+actual);
            if(Math.abs(actual-requested)>PtcPlan.LINE_NS+1)throw new IOException("PTC实际曝光偏离请求超过一行："+actual+" / "+requested);
        }
        // All metadata are checked before creating the first visible DNG.
        Set<Long> times=new HashSet<>();for(Pair p:pairs)if(!times.add(p.meta.timestamp))throw new IOException("Duplicate temporal group");
        Collections.sort(pairs,Comparator.comparingLong(p->p.meta.timestamp));
        long requiredBytes=pairs.size()*101_000_000L+250_000_000L;if(context.getFilesDir().getUsableSpace()<requiredBytes)throw new IOException("导出空间不足，至少需 "+(requiredBytes/1024/1024)+" MB");
        ArrayList<Uri> pending=new ArrayList<>();int count=0;String folder="Native_ISO50_"+id;
        boolean unity=prefs.getBoolean("sessionUnity",false);
        int kindsCount=ptc==null&&!unity?3:2;
        if(ptc!=null)folder=ptc.getString("batch")+"/L"+String.format(Locale.US,"%03d",ptc.getInt("level"))+"_"+ptc.getString("shot");
        try {
            for(Pair p:pairs){
                progress.say("ISO 50 校验通过。正在保存第 "+(++count)+" / "+pairs.size()+" 个时间点，每组 "+kindsCount+" 张 DNG…");
                File a=fetch(p.a),b=fetch(p.b);String base="JC_"+p.prefix+"_T"+String.format(Locale.US,"%02d",p.index/2)+"_ISO50";
                String s=p.prefix;String date=s.substring(0,4)+":"+s.substring(4,6)+":"+s.substring(6,8)+" "+s.substring(8,10)+":"+s.substring(10,12)+":"+s.substring(12,14);
                String[] kinds={"RAW14_Original","RAW10_Paired_Original","SameTime_HDR_EXPERIMENTAL_UInt16"};JSONArray files=new JSONArray();
                try{for(int k=0;k<kindsCount;k++){
                    String name=base+"_"+kinds[k]+".dng";Uri u=output(folder,name,"image/x-adobe-dng");pending.add(u);
                    byte[] template;try(InputStream in=context.getAssets().open("dng_"+k+".header")){template=NativeRaw.bytes(in,16384);}
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    try(OutputStream out=new java.security.DigestOutputStream(new BufferedOutputStream(context.getContentResolver().openOutputStream(u)),digest)){NativeRaw.write(a,b,out,template,p.meta,k,date);}
                    files.put(new JSONObject().put("name",name).put("sha256",hex(digest.digest())));
                }}finally{a.delete();b.delete();}
                JSONObject report=new JSONObject().put("focusMode",prefs.getBoolean("sessionInfinity",false)?"factory_infinity_fixed":"auto").put("fixedFocusStep",prefs.getBoolean("sessionInfinity",false)?824:JSONObject.NULL).put("factoryInfinityDAC",prefs.getBoolean("sessionInfinity",false)?552:JSONObject.NULL).put("ISO",p.meta.iso).put("sensorMode",p.meta.mode).put("timestampNs",p.meta.timestamp).put("exposureNs",p.meta.exposure).put("temporalGroupsUsedPerDng",1).put("nativeSessionGroups",pairs.size()).put("frame",p.frame).put("mainPackedSha256",p.a.hash).put("pairedPackedSha256",p.b.hash).put("branchScaleEmpirical",120).put("storageBits",16).put("uniformOutputScale",0.5).put("whiteLevel",58052).put("maximumStorageRoundingErrorCodes",0.5).put("fullRangeLinearityValidated",false).put("darkQuantizationResolved",false).put("effective14bitPrecisionProven",false).put("files",files).put("note","ISO50 fixed. Raw branches lossless. Experimental HDR uses only this same-time pair. Output round(merged*0.5) is uniform scale, no gamma/tone curve. Ratio120 and RAW10 black64 empirical; overlap has limited validation, no independent full-range radiometric calibration. UInt16 storage is not 16 effective ADC bits. No temporal merge.");
                String ettr=prefs.getString("ettr",null);if(ettr!=null)report.put("ettr",new JSONObject(ettr));
                report.put("captureLifecycle",lifecycle);
                report.put("loficUnity",unity).put("loficGainSetting",unity?"mode5_unity_floor":"stock");
                if(unity)report.put("sensorPatchSha256","3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233");
                if(ptc!=null||unity){if(ptc!=null)report.put("ptc",ptc);report.put("uniformOutputScale",1).put("maximumStorageRoundingErrorCodes",0).put("branchWhiteLevels",new JSONArray(new int[]{16383,1023})).put("note","Two losslessly unpacked original RAW branches from one timestamp; no HDR merge, black subtraction, denoise or requantization. LOFIC unity mode needs a new exposure-dependent merge calibration. ISO fixed 50.");report.remove("whiteLevel");report.remove("branchScaleEmpirical");}
                Uri u=output(folder,base+"_metadata.json","application/json");pending.add(u);try(OutputStream out=context.getContentResolver().openOutputStream(u)){out.write(report.toString(2).getBytes("UTF-8"));}
            }
            for(Uri u:pending){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);if(context.getContentResolver().update(u,v,null,null)!=1)throw new IOException("Failed to publish complete batch");}
            lastExportExposureNs=pairs.get(0).meta.exposure;
            prefs.edit().remove("session").remove("pending").commit();return pairs.size()*kindsCount;
        }catch(Exception e){for(Uri u:pending)try{context.getContentResolver().delete(u,null,null);}catch(Exception ignored){}throw e;}
    }
}
