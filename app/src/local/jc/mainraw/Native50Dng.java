package local.jc.mainraw;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.*;

/** Lossless unpacking only. CFA, black and white are retained at native codes. */
final class Native50Dng {
    static JSONObject export(Context context,File folder,JSONObject capture,boolean quad)throws Exception {
        JSONObject raw=capture.getJSONObject("raw");
        File input=new File(raw.getString("path")),output=new File(folder,quad?"native_qbayer_50mp.dng":"official_bayer_50mp.dng");
        byte[] header;try(InputStream in=context.getAssets().open(quad?"native50_quad.header":"native50_bayer.header")){header=NativeRaw.bytes(in,16384);}
        ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int ifd=b.getInt(4),count=b.getShort(ifd)&65535;
        for(int i=0;i<count;i++){
            int at=ifd+2+i*12,tag=b.getShort(at)&65535;
            if(tag==33434){int ptr=b.getInt(at+8);long ns=capture.getLong("actualExposureNs");b.putInt(ptr,(int)ns);b.putInt(ptr+4,1000000000);}
        }
        int stride=raw.getInt("rowStride");byte[] row=new byte[stride],decoded=new byte[8192*2];
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(input)));OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
            out.write(header);
            for(int y=0;y<6144;y++){
                in.readFully(row);
                for(int x=0;x<8192;x+=4){int off=x/4*5,lsb=row[off+4]&255;for(int lane=0;lane<4;lane++){int v=((row[off+lane]&255)<<2)|((lsb>>(2*lane))&3);decoded[(x+lane)*2]=(byte)v;decoded[(x+lane)*2+1]=(byte)(v>>8);}}
                out.write(decoded);
            }
            if(in.read()!=-1)throw new IOException("RAW has trailing rows");
        }
        return new JSONObject().put("name",output.getName()).put("sha256",Native50Store.hash(output)).put("storageBits",16).put("codeBits",10).put("blackLevel",64).put("whiteLevel",1023)
            .put("cfa",quad?"BGGB/BGGB/GRRG/GRRG":"BG/GR").put("colorCalibration","same-camera stock DNG template; neutral WB; not a per-scene calibration").put("pixelTransform","RAW10 lossless unpack only");
    }
}
