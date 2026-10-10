package local.jc.mainraw;
import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;

final class ClassicHdrPublish {
    static synchronized void publish(Context c,File folder,JSONObject result)throws Exception {
        if(new File(folder,"published.json").isFile())return;
        if(!PairCaptureStore.read(new File(folder,"restored.json")).getBoolean("restored"))throw new IOException("Restoration not confirmed");
        File pending=new File(folder,"publish_pending.json");
        if(pending.isFile()){JSONArray old=PairCaptureStore.read(pending).getJSONArray("uris");for(int i=0;i<old.length();i++)c.getContentResolver().delete(Uri.parse(old.getString(i)),null,null);}
        JSONArray uris=new JSONArray(),dng=result.getJSONArray("dng");
        for(int i=0;i<dng.length();i++){
            JSONObject spec=dng.getJSONObject(i);File source=new File(folder,spec.getString("name"));if(!Native50Store.hash(source).equals(spec.getString("sha256")))throw new IOException("DNG changed");
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,source.getName());values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/JCCamera/ClassicHDR/"+folder.getName());values.put(MediaStore.MediaColumns.MIME_TYPE,"image/x-adobe-dng");values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri u=c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(u==null)throw new IOException("Download insert failed");uris.put(u.toString());PairCaptureStore.atomic(pending,new JSONObject().put("uris",uris));
            try(InputStream in=new FileInputStream(source);OutputStream out=c.getContentResolver().openOutputStream(u)){if(out==null)throw new IOException("Download output missing");byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        }
        for(int i=0;i<uris.length();i++){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);if(c.getContentResolver().update(Uri.parse(uris.getString(i)),v,null,null)!=1)throw new IOException("Publish failed");}
        PairCaptureStore.atomic(new File(folder,"published.json"),new JSONObject().put("uris",uris));pending.delete();
    }
}
