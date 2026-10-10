package local.jc.mainraw;

import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable per-shutter evidence and previews made from the actual saved RAWs. */
final class PairCaptureStore {
    static JSONObject value(JSONArray events, String type, long timestamp) throws Exception {
        JSONObject found = null;
        for (int i=0;i<events.length();i++) {
            JSONObject e=events.getJSONObject(i), v=e.optJSONObject("value");
            if (type.equals(e.optString("type")) && v!=null && (timestamp<0 || v.optLong("timestampNs",-2)==timestamp)) {
                if (found!=null) throw new IOException("Duplicate capture evidence");
                found=v;
            }
        }
        return found;
    }
    static JSONObject validate(File folder, JSONArray events) throws Exception {
        JSONObject capturePlan=read(new File(folder,"plan.json"));
        boolean tripod="tripod".equals(capturePlan.optString("samplingMode"));
        boolean manual="manual".equals(capturePlan.optString("samplingMode"));
        JSONObject quick=tripod?value(events,"tripod_quick_result",-1):null;
        JSONObject ettr=tripod?value(events,"tripod_ettr_plan",-1):null;
        JSONObject h=value(events,"bridge_hdr_result",-1), m=null;
        for (int i=0;i<events.length();i++) {
            JSONObject e=events.getJSONObject(i), v=e.optJSONObject("value");
            if ("error".equals(e.optString("type"))) throw new IOException("Capture reported failure");
            if ("native_probe_result".equals(e.optString("type")) && v!=null && v.optBoolean("snapshot")) {
                if(m!=null)throw new IOException("Multiple 50MP shutters"); m=v;
            }
        }
        if(h==null || m==null)return null;
        long ht=h.getLong("timestampNs"), mt=m.getLong("timestampNs");
        JSONObject a=value(events,"native_raw_preview_saved",ht), b=value(events,"companion_preview_saved",ht), c=value(events,"mp50_preview_saved",mt);
        if(a==null || b==null || c==null || value(events,"native_snapshot_result",mt)==null)return null;
        if(value(events,"bridge_hdr_started",ht)==null || value(events,"native_snapshot_started",mt)==null)return null;
        if(value(events,"native_snapshot_request",-1)==null || value(events,"bridge_hdr_request",-1)==null)return null;
        if(ht<=mt || ht-mt>(manual?10_000_000_000L:tripod?5_000_000_000L:2_000_000_000L))throw new IOException("Pair order or delay rejected");
        JSONObject hs=h.getJSONObject("sensorApplied"), ms=m.getJSONObject("sensorApplied");
        if(tripod){
            if(quick==null||ettr==null)return null;
            if(!PairExposurePolicy.tripod50(m.getLong("exposureNs"),ms.getJSONArray("exposureNs").getLong(0))||m.getInt("iso")!=70)
                throw new IOException("Tripod minimum ISO/exposure rejected");
            if(Math.abs(ms.getJSONArray("exposureNs").getLong(0)-capturePlan.getLong("mp50ExposureNs"))>PairExposurePolicy.HDR_LINE_NS)
                throw new IOException("50MP metered exposure was not applied");
            long qt=quick.getLong("timestampNs");
            if(qt<=mt||qt>=ht||ettr.getLong("quickTimestampNs")!=qt)throw new IOException("Tripod order rejected");
            JSONObject q10=value(events,"companion_preview_saved",qt),q14=value(events,"native_raw_preview_saved",qt);
            if(q10==null||q14==null)return null;
            if(value(events,"tripod_quick_started",qt)==null||value(events,"tripod_quick_request",-1)==null)throw new IOException("Missing quick shutter");
            validateTripodHdr(quick,NativeEttr.FIRST_NS,false);
            long qe=quick.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0);
            boolean channelRule=NativeEttr.CHANNEL_RULE.equals(ettr.optString("rule"));
            if(ettr.has("rule")&&!channelRule)throw new IOException("Unknown ETTR rule");
            NativeEttr.Result measured=channelRule?NativeEttr.measureChannels(new File(folder,new File(q10.getString("path")).getName()),qe):NativeEttr.measure(new File(folder,new File(q10.getString("path")).getName()),qe,true);
            if(channelRule){
                JSONArray means=ettr.getJSONArray("channelTop10Means");
                if(means.length()!=4||!NativeEttr.CHANNELS[measured.selectedChannel].equals(ettr.getString("selectedChannel")))throw new IOException("Channel selection mismatch");
                for(int i=0;i<4;i++)if(means.getDouble(i)!=measured.channelMeans[i])throw new IOException("Channel Top10 mismatch");
            }
            measured.exposureNs=Math.max(NativeEttr.FIRST_NS,measured.exposureNs);
            if(measured.exposureNs!=ettr.getLong("slowExposureNs")||measured.mean!=ettr.getDouble("top10Mean"))throw new IOException("ETTR calculation mismatch");
            validateTripodHdr(h,measured.exposureNs,false);
            if(capturePlan.getLong("preparationElapsedMs")-capturePlan.getLong("pressedElapsedMs")<4000)throw new IOException("Countdown shorter than 4 seconds");
        }else if(manual){
            if(m.getInt("iso")!=70||!PairExposurePolicy.tripod50(m.getLong("exposureNs"),ms.getJSONArray("exposureNs").getLong(0))
                    ||Math.abs(ms.getJSONArray("exposureNs").getLong(0)-capturePlan.getLong("mp50ExposureNs"))>PairExposurePolicy.HDR_LINE_NS)throw new IOException("Manual 50MP exposure rejected");
            validateTripodHdr(h,capturePlan.getLong("hdrExposureNs"),true);
            long delay=capturePlan.getLong("delayMs");
            if(delay<0||delay>60000||capturePlan.getLong("preparationElapsedMs")-capturePlan.getLong("pressedElapsedMs")<delay)throw new IOException("Manual countdown rejected");
            if(value(events,"tripod_quick_request",-1)!=null)throw new IOException("Unexpected manual metering shutter");
        }else if(!PairExposurePolicy.safe50(m.getLong("exposureNs"),ms.getJSONArray("exposureNs").getLong(0))
                || !PairExposurePolicy.fixedHdr(hs.getJSONArray("exposureNs").getLong(0)))throw new IOException("Safety shutter rejected");
        if(!h.optBoolean("driverMetadataVerified") || h.getInt("actualSensorMode")!=5 || m.getInt("actualSensorMode")!=0
                || !c.optBoolean("payloadComplete") || h.getInt("iso")!=50 || m.getInt("iso")<50
                || h.getInt("postRawBoost")!=100 || m.getInt("postRawBoost")!=100
                || !h.optBoolean("physicalResultPresent") || !m.optBoolean("physicalResultPresent")
                || !h.optBoolean("focusVerified",h.optBoolean("factoryInfinityVerified")) || !m.optBoolean("focusVerified",m.optBoolean("factoryInfinityVerified")))throw new IOException("Mode, gain or focus rejected");
        if(m.has("requestedFocusDistance")) {
            JSONObject plan=read(new File(folder,"plan.json")).getJSONObject("focus");float requested=(float)plan.getDouble("focusDistance");
            if(plan.getInt("afState")!=4)throw new IOException("Preview AF was not locked");
            for(JSONObject frame:tripod?new JSONObject[]{m,quick,h}:new JSONObject[]{m,h})if(frame.getJSONObject("focusEvidence").optInt("stableActuatorDac",-1)<0
                    ||!PairFocusPolicy.matches(requested,(float)frame.getDouble("requestedFocusDistance"))
                    ||!PairFocusPolicy.matches(requested,(float)frame.getDouble("focusDistance")))throw new IOException("Capture focus differs from locked preview");
        }
        JSONObject actual=h.getJSONObject("actual");
        if(actual.getJSONArray("com.qti.stats_control.DCGMode").getInt(0)!=5 || actual.getJSONArray("com.qti.stats_control.ExposureCount").getInt(0)!=2)
            throw new IOException("HDR branches rejected");
        for(String k:new String[]{"analogGain","digitalGain","ispGain"}) {
            JSONArray hg=hs.getJSONArray(k), mg=ms.getJSONArray(k);
            if(hg.getDouble(0)!=1 || hg.getDouble(1)!=0 || hg.getDouble(2)!=1 || mg.getDouble(0)<1 || mg.getDouble(0)>16 || ((tripod||manual)&&mg.getDouble(0)!=1))
                throw new IOException("Applied gain rejected");
        }
        if(Math.abs(hs.getJSONArray("exposureNs").getLong(0)-hs.getJSONArray("exposureNs").getLong(2))>(manual?PairManualPolicy.roundingTolerance(hs.getJSONArray("exposureNs").getLong(0)):tripod?1:0) || hs.getJSONArray("exposureNs").getLong(1)!=0)
            throw new IOException("HDR exposure pairing rejected");
        JSONArray files=new JSONArray();
        ArrayList<Object[]> specs=new ArrayList<>();specs.add(new Object[]{a,22020096L});specs.add(new Object[]{b,15728640L});specs.add(new Object[]{c,62914560L});
        if(tripod){long qt=quick.getLong("timestampNs");specs.add(new Object[]{value(events,"native_raw_preview_saved",qt),22020096L});specs.add(new Object[]{value(events,"companion_preview_saved",qt),15728640L});}
        for(Object[] spec:specs) {
            JSONObject item=(JSONObject)spec[0]; File file=new File(folder,new File(item.getString("path")).getName());
            if(file.length()!=(Long)spec[1])throw new IOException("Incomplete RAW file");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)digest.update(buf,0,n);}
            StringBuilder hash=new StringBuilder();for(byte v:digest.digest())hash.append(String.format(Locale.US,"%02x",v&255));
            files.put(new JSONObject().put("name",file.getName()).put("bytes",file.length()).put("sha256",hash));
        }
        return new JSONObject().put("formatVersion",1).put("complete",true).put("order","50MP_then_LOFIC_DCG")
                .put("mp50",m).put("hdr",h).put("intervalNs",ht-mt).put("files",files)
                .put("samplingMode",manual?"manual":tripod?"tripod":"handheld").put("quick",quick).put("ettr",ettr)
                .put("hdrRequestedExposureNs",manual?capturePlan.getLong("hdrExposureNs"):tripod?ettr.getLong("slowExposureNs"):PairExposurePolicy.SHUTTER_NS).put("hdrLineToleranceNs",PairExposurePolicy.HDR_LINE_NS);
    }
    static void validateTripodHdr(JSONObject h,long expected,boolean manual)throws Exception {
        JSONObject s=h.getJSONObject("sensorApplied"),a=h.getJSONObject("actual");JSONArray ex=s.getJSONArray("exposureNs");
        if(!h.optBoolean("driverMetadataVerified")||h.getInt("actualSensorMode")!=5||h.getInt("iso")!=50||h.getInt("postRawBoost")!=100
                ||!h.optBoolean("physicalResultPresent")||!h.optBoolean("focusVerified")||h.getLong("pairRequestedExposureNs")!=expected
                ||!PairExposurePolicy.hdrMatches(expected,ex.getLong(0))||Math.abs(ex.getLong(0)-ex.getLong(2))>(manual?PairManualPolicy.roundingTolerance(ex.getLong(0)):1)||ex.getLong(1)!=0
                ||a.getJSONArray("com.qti.stats_control.DCGMode").getInt(0)!=5||a.getJSONArray("com.qti.stats_control.ExposureCount").getInt(0)!=2)
            throw new IOException("Tripod HDR metadata rejected");
        for(String k:new String[]{"analogGain","digitalGain","ispGain"}){JSONArray g=s.getJSONArray(k);if(g.getDouble(0)!=1||g.getDouble(1)!=0||g.getDouble(2)!=1)throw new IOException("Tripod HDR gain rejected");}
    }
    static void atomic(File file, JSONObject value) throws Exception {
        File temp=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.toString(2).getBytes("UTF-8"));out.getFD().sync();}
        android.system.Os.rename(temp.getAbsolutePath(),file.getAbsolutePath());
    }
    static JSONObject read(File file) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        return new JSONObject(out.toString("UTF-8"));
    }
    static void previews(File folder, JSONObject result) throws Exception {
        JSONArray files=result.getJSONArray("files");
        thumbnail(new File(folder,files.getJSONObject(2).getString("name")),10,8192,6144,true,null,new File(folder,"50mp.jpg"));
        thumbnail(new File(folder,files.getJSONObject(0).getString("name")),14,4096,3072,false,new File(folder,files.getJSONObject(1).getString("name")),new File(folder,"hdr.jpg"));
    }
    static void thumbnail(File raw,int bits,int width,int height,boolean quad,File companion,File jpeg)throws Exception {
        if(jpeg.isFile())return;
        int step=quad?8:4, ow=width/step, oh=height/step; float[] rgb=new float[ow*oh*3];
        int stride=width*bits/8; byte[] row=new byte[stride];int[] unpack=new int[width];
        int[][] tile=quad?new int[][]{{2,1,1,2},{2,1,1,2},{1,0,0,1},{1,0,0,1}}:new int[][]{{2,1},{1,0}};
        int period=tile.length;float black=bits==14?1024:64;
        try(RandomAccessFile in=new RandomAccessFile(raw,"r"); RandomAccessFile paired=companion==null?null:new RandomAccessFile(companion,"r")) {
            byte[] pairedRow=new byte[width*10/8];int[] pairedPixels=new int[width];
            for(int y=0;y<oh;y++)for(int dy=0;dy<period;dy++) {
                in.seek((long)(y*step+dy)*stride);in.readFully(row);NativeRaw.unpack(row,bits,unpack);
                if(paired!=null){paired.seek((long)(y*step+dy)*pairedRow.length);paired.readFully(pairedRow);NativeRaw.unpack(pairedRow,10,pairedPixels);}
                for(int x=0;x<ow;x++)for(int dx=0;dx<period;dx++){
                    int color=tile[dy][dx], count=color==1?period*period/2:period*period/4;
                    rgb[(y*ow+x)*3+color]+=Math.max(0,(paired==null?unpack[x*step+dx]:NativeRaw.merge(unpack[x*step+dx],pairedPixels[x*step+dx]))-black)/count;
                }
            }
        }
        float[] light=new float[ow*oh];double[] means=new double[3];
        for(int i=0;i<light.length;i++){light[i]=Math.max(rgb[i*3],Math.max(rgb[i*3+1],rgb[i*3+2]));for(int c=0;c<3;c++)means[c]+=rgb[i*3+c];}
        Arrays.sort(light);double white=Math.max(16,light[(int)(light.length*.99)]);int[] colors=new int[ow*oh];
        for(int i=0;i<colors.length;i++) {int packed=0xff000000;for(int c=0;c<3;c++){
            double wb=Math.max(.5,Math.min(2.5,means[1]/Math.max(1,means[c])));
            int v=(int)Math.round(255*Math.pow(Math.min(1,rgb[i*3+c]*wb/white),1/2.2));packed|=v<<(16-8*c);
        }colors[i]=packed;}
        Bitmap bitmap=Bitmap.createBitmap(colors,ow,oh,Bitmap.Config.ARGB_8888);
        android.graphics.Matrix rotate=new android.graphics.Matrix();rotate.postRotate(90);
        Bitmap portrait=Bitmap.createBitmap(bitmap,0,0,ow,oh,rotate,true);
        File temp=new File(jpeg.getParentFile(),jpeg.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){if(!portrait.compress(Bitmap.CompressFormat.JPEG,94,out))throw new IOException("Preview encoding failed");}
        bitmap.recycle();portrait.recycle();android.system.Os.rename(temp.getAbsolutePath(),jpeg.getAbsolutePath());
    }
}
