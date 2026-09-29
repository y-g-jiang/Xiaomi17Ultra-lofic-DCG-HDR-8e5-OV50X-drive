package local.jc.mainraw;
import android.app.*;
import android.content.*;
import android.os.*;

/** Keeps the user-initiated capture alive while the stock camera is foreground. */
public final class CaptureService extends Service {
    @Override public void onCreate(){super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("capture","RAW 采集",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,NativePhotoActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(50,new Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("正在采集单组 RAW").setContentText("ISO 50 · 完成后自动返回并保存 DNG").setContentIntent(open).setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent i,int flags,int id){return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}
}
