package local.jc.mainraw;

import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Two independent sensor exposures; no software remosaic of the native data. */
final class Native50Store {
    static JSONObject validate(File folder,JSONArray events)throws Exception {
        JSONObject m=null;
        for(int i=0;i<events.length();i++){
            JSONObject e=events.getJSONObject(i),v=e.optJSONObject("value");
            if("error".equals(e.optString("type")))throw new IOException("capture error; no retry");
            if("native_probe_result".equals(e.optString("type"))&&v!=null&&v.optBoolean("snapshot")){
                if(m!=null)throw new IOException("more than one shutter");m=v;
            }
        }
        if(m==null)return null;
        long ts=m.getLong("timestampNs");
        JSONObject raw=PairCaptureStore.value(events,"mp50_preview_saved",ts);
        if(raw==null||PairCaptureStore.value(events,"native_snapshot_result",ts)==null||PairCaptureStore.value(events,"native_snapshot_started",ts)==null)return null;
        if(PairCaptureStore.value(events,"native_snapshot_request",-1)==null)throw new IOException("missing snapshot request");
        if(m.getInt("actualSensorMode")!=0||m.getInt("iso")!=70||!m.optBoolean("native50MinimumGainVerified")||!m.getBoolean("focusVerified")||!m.getBoolean("physicalResultPresent"))throw new IOException("mode0/unity/focus rejected");
        JSONObject applied=m.getJSONObject("sensorApplied");
        for(String key:new String[]{"analogGain","digitalGain","ispGain"})if(applied.getJSONArray(key).getDouble(0)!=1)throw new IOException("gain is not minimum unity");
        long exposure=applied.getJSONArray("exposureNs").getLong(0);
        if(exposure<=0||Math.abs(exposure-m.getLong("requestedExposureNs"))>31000)throw new IOException("actual shutter differs from frozen request");
        if(!raw.getBoolean("payloadComplete")||raw.getInt("width")!=8192||raw.getInt("height")!=6144||raw.getInt("format")!=37||raw.getInt("bytes")!=62914560)throw new IOException("incomplete RAW10");
        File packed=new File(folder,new File(raw.getString("path")).getName());
        if(packed.length()!=62914560)throw new IOException("packed RAW length");
        return new JSONObject().put("capture",m).put("raw",raw).put("packedSha256",hash(packed)).put("actualExposureNs",exposure);
    }
    static String hash(File file)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)d.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
    }
    static JSONObject pair(File folder)throws Exception {
        JSONObject plan=PairCaptureStore.read(new File(folder,"plan.json"));
        JSONObject a=PairCaptureStore.read(new File(folder,"official/complete.json")),b=PairCaptureStore.read(new File(folder,"qbayer/complete.json"));
        JSONObject am=a.getJSONObject("capture"),bm=b.getJSONObject("capture");
        if(bm.getLong("timestampNs")<=am.getLong("timestampNs"))throw new IOException("pair order or duplicate timestamp");
        float focus=(float)plan.getDouble("focusDistance");
        if(a.getLong("actualExposureNs")!=b.getLong("actualExposureNs")||am.getInt("iso")!=bm.getInt("iso"))throw new IOException("pair parameters differ");
        long requested=plan.getLong("exposureNs");
        if(requested<100_000L||requested>1_000_000_000L)throw new IOException("pair shutter range");
        if("manual".equals(plan.optString("exposureMode"))&&plan.getLong("manualExposureNs")!=requested)throw new IOException("manual shutter plan differs");
        for(JSONObject m:new JSONObject[]{am,bm}){
            if(m.getLong("requestedExposureNs")!=requested||Math.abs(m.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0)-requested)>31000)throw new IOException("pair shutter differs from plan");
            if(!PairFocusPolicy.matches(focus,(float)m.getDouble("focusDistance")))throw new IOException("pair focus differs");
        }
        if(am.getJSONObject("focusEvidence").getInt("stableActuatorDac")!=bm.getJSONObject("focusEvidence").getInt("stableActuatorDac"))throw new IOException("pair actuator commands differ");
        if(am.getInt("timestampSource")!=1||am.getLong("timestampNs")<(plan.getLong("pressedElapsedMs")+10000)*1000000L)throw new IOException("first exposure before ten second deadline");
        return new JSONObject().put("complete",true).put("official",a).put("qbayer",b).put("plan",plan).put("intervalNs",bm.getLong("timestampNs")-am.getLong("timestampNs"));
    }
}
