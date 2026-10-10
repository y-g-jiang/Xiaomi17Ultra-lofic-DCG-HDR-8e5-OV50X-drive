package local.jc.mainraw;
import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.util.*;

/** Copy verified outputs into ordinary Downloads, with interrupted copies hidden. */
final class Native50Publish {
    static synchronized void publish(Context context,File folder)throws Exception {
        if(new File(folder,"published.json").isFile())return;
        if(!PairCaptureStore.read(new File(folder,"restored.json")).getBoolean("restored"))throw new IOException("restoration not confirmed");
        JSONObject pair=PairCaptureStore.read(new File(folder,"complete.json"));
        File journal=new File(folder,"publish_pending.json");
        if(journal.isFile()){JSONArray old=PairCaptureStore.read(journal).getJSONArray("uris");for(int i=0;i<old.length();i++)context.getContentResolver().delete(Uri.parse(old.getString(i)),null,null);}
        JSONArray uris=new JSONArray();
        for(String layout:new String[]{"official","qbayer"}){
            String name=pair.getJSONObject(layout).getJSONObject("dng").getString("name");File source=new File(folder,layout+"/"+name);
            if(!Native50Store.hash(source).equals(pair.getJSONObject(layout).getJSONObject("dng").getString("sha256")))throw new IOException("DNG changed before publishing");
            ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/JCCamera/Native50/"+folder.getName());v.put(MediaStore.MediaColumns.MIME_TYPE,"image/x-adobe-dng");v.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException("cannot create Download output");uris.put(uri.toString());PairCaptureStore.atomic(journal,new JSONObject().put("uris",uris));
            try(InputStream in=new FileInputStream(source);OutputStream out=context.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("cannot open Download output");byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        }
        for(int i=0;i<uris.length();i++){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);if(context.getContentResolver().update(Uri.parse(uris.getString(i)),v,null,null)!=1)throw new IOException("cannot publish Download");}
        PairCaptureStore.atomic(new File(folder,"published.json"),new JSONObject().put("uris",uris).put("folder","Download/JCCamera/Native50/"+folder.getName()));journal.delete();
    }
}
