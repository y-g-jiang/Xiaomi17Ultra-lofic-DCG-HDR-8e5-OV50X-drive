package local.jc.mainraw;

import java.io.*;
import java.lang.reflect.Method;

/** Fresh mounted-file hashes using Android SHA-256 under root read access. */
public final class PairFileAuditMain {
    public static void main(String[] args)throws Exception {
        Method get=Class.forName("android.os.SystemProperties").getMethod("get",String.class);
        String key="init.svc_debug_pid.vendor.camera-provider";
        System.out.println(get.invoke(null,key));
        for(String path:new String[]{"/odm/etc/camera/camxoverridesettings.txt","/vendor/lib64/hw/camera.qcom.core.so","/odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so"})
            System.out.println(Native50Store.hash(new File(path))+"  "+path);
        System.out.println(get.invoke(null,key));
    }
}
