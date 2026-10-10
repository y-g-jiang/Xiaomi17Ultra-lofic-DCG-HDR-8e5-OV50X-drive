package local.jc.mainraw;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** One exposure, two original streams. Never accepts a request as hardware proof. */
final class ClassicHdrStore {
    static JSONObject validate(File folder,JSONArray events)throws Exception {
        JSONObject plan=PairCaptureStore.read(new File(folder,"plan.json"));
        if(!"classic_hdr".equals(plan.optString("samplingMode")))throw new IOException("Wrong capture plan");
        int shutters=0;for(int i=0;i<events.length();i++){
            String t=events.getJSONObject(i).optString("type");
            if("error".equals(t))throw new IOException("Capture reported failure");
            if("native_snapshot_request".equals(t)||"tripod_quick_request".equals(t)||"bridge_mode_transition".equals(t))throw new IOException("Extra capture or mode switch");
            if("bridge_hdr_request".equals(t))shutters++;
        }
        if(shutters>1)throw new IOException("Multiple shutters");
        JSONObject h=PairCaptureStore.value(events,"bridge_hdr_result",-1);if(h==null)return null;
        long ts=h.getLong("timestampNs");
        JSONObject a=PairCaptureStore.value(events,"native_raw_preview_saved",ts),b=PairCaptureStore.value(events,"companion_preview_saved",ts);
        if(a==null||b==null||PairCaptureStore.value(events,"bridge_hdr_started",ts)==null||shutters!=1)return null;
        long request=plan.getLong("hdrExposureNs"),delay=plan.getLong("delayMs");
        if(request<100000||request>1000000000||delay<0||delay>60000||plan.getLong("preparationElapsedMs")-plan.getLong("pressedElapsedMs")<delay)throw new IOException("Invalid exposure/countdown");
        PairCaptureStore.validateTripodHdr(h,request,true);
        float focus=(float)plan.getJSONObject("focus").getDouble("focusDistance");
        if(plan.getJSONObject("focus").getInt("afState")!=4||!PairFocusPolicy.matches(focus,(float)h.getDouble("requestedFocusDistance"))
                ||!PairFocusPolicy.matches(focus,(float)h.getDouble("focusDistance"))||h.getJSONObject("focusEvidence").optInt("stableActuatorDac",-1)<0)throw new IOException("Locked focus not applied");
        JSONArray files=new JSONArray();JSONObject[] items={a,b};long[] sizes={22020096L,15728640L};
        for(int i=0;i<2;i++){
            File f=new File(folder,new File(items[i].getString("path")).getName());
            if(f.length()!=sizes[i])throw new IOException("Incomplete original RAW");
            String sha=Native50Store.hash(f);if(!sha.equals(items[i].getString("sha256")))throw new IOException("RAW hash mismatch");
            files.put(new JSONObject().put("name",f.getName()).put("sha256",sha).put("bytes",f.length()).put("timestampNs",ts));
        }
        LoficHighlights.Result r=LoficHighlights.measure(new File(folder,files.getJSONObject(1).getString("name")));
        JSONObject highlights=new JSONObject().put("source","complete_original_lofic_raw10").put("whiteLevel",1023).put("blackLevel",64)
            .put("channels",new JSONArray(NativeEttr.CHANNELS)).put("channelClippedPixels",new JSONArray(r.clipped)).put("channelTop10Means",new JSONArray(r.top10))
            .put("pixelsPerChannel",LoficHighlights.CHANNEL_PIXELS).put("allFourClipped2x2Cells",r.allChannelsClippedCells)
            .put("bestChannel",NativeEttr.CHANNELS[r.bestChannel]).put("bestChannelHeadroomEv",Double.isFinite(r.headroomEv)?r.headroomEv:JSONObject.NULL)
            .put("headroomScope","Linear code-domain estimate from the least-clipped channel Top10, not a guarantee of color recovery or exact exposure ratio.");
        return new JSONObject().put("formatVersion",1).put("complete",true).put("samplingMode","classic_hdr").put("order","same_timestamp_RAW14_RAW10")
            .put("hdr",h).put("files",files).put("highlights",highlights).put("hdrRequestedExposureNs",request);
    }
    static JSONArray dng(Context c,File folder,JSONObject result)throws Exception {
        JSONObject h=result.getJSONObject("hdr");JSONArray files=result.getJSONArray("files");
        File r14=new File(folder,files.getJSONObject(0).getString("name")),r10=new File(folder,files.getJSONObject(1).getString("name"));
        NativeRaw.Metadata m=new NativeRaw.Metadata();m.timestamp=h.getLong("timestampNs");m.exposure=h.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(0);m.iso=50;m.mode=5;
        JSONArray black=h.getJSONArray("raw14BlackLevels");if(black.length()!=4)throw new IOException("Missing black levels");m.black=new float[4];
        for(int i=0;i<4;i++){m.black[i]=(float)black.getDouble(i);if(!Float.isFinite(m.black[i])||m.black[i]<1023||m.black[i]>1026)throw new IOException("Unexpected RAW14 black level");}
        String date=new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US).format(new Date());JSONArray out=new JSONArray();
        for(int kind=0;kind<2;kind++){
            byte[] template;try(InputStream in=c.getAssets().open("dng_"+kind+".header")){template=NativeRaw.bytes(in,16384);}
            // Use actual AWB gains when reported; retain explicit template calibration otherwise.
            JSONArray neutral=h.optJSONArray("asShotNeutral");m.neutral=new int[6];
            if(neutral!=null&&neutral.length()==6){for(int i=0;i<6;i++)m.neutral[i]=neutral.getInt(i);}
            else{ByteBuffer bb=ByteBuffer.wrap(template).order(ByteOrder.LITTLE_ENDIAN);int ifd=bb.getInt(4),n=bb.getShort(ifd)&65535;boolean found=false;
                for(int i=0;i<n;i++){int at=ifd+2+i*12;if((bb.getShort(at)&65535)==50728){int ptr=bb.getInt(at+8);for(int k=0;k<6;k++)m.neutral[k]=bb.getInt(ptr+4*k);found=true;break;}}if(!found)throw new IOException("Missing neutral calibration");}
            File target=new File(folder,kind==0?"dcg_raw14.dng":"lofic_raw10.dng"),temp=new File(folder,target.getName()+".tmp");
            // Keep each branch's actual reported exposure, including bounded integer differences.
            m.exposure=h.getJSONObject("sensorApplied").getJSONArray("exposureNs").getLong(kind==0?0:2);
            try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(temp))){NativeRaw.write(r14,r10,stream,template,m,kind,date);}
            android.system.Os.rename(temp.getAbsolutePath(),target.getAbsolutePath());
            out.put(new JSONObject().put("name",target.getName()).put("sha256",Native50Store.hash(target)).put("storageBits",16).put("codeBits",kind==0?14:10)
                .put("blackLevel",kind==0?new JSONArray(m.black):64).put("whiteLevel",kind==0?16383:1023).put("exposureNs",m.exposure)
                .put("pixelTransform","lossless original MIPI unpack only").put("whiteBalanceSource",neutral!=null?"actual_camera2_awb_gains":"same_camera_template"));
        }
        return out;
    }
    static void preview(File folder,JSONObject result)throws Exception {
        JSONArray f=result.getJSONArray("files");
        PairCaptureStore.thumbnail(new File(folder,f.getJSONObject(0).getString("name")),14,4096,3072,false,new File(folder,f.getJSONObject(1).getString("name")),new File(folder,"hdr.jpg"));
    }
}
