package local.jc.mainraw;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Dedicated root child. Fixed properties only; no writes until the parent's GO. */
public final class FastHybridBridgePropertyMain {
    public static void main(String[] args) throws Exception {
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(3);
        }, "JC-Bridge-Deadline");
        deadline.setDaemon(true);
        deadline.start();
        Class<?> properties = Class.forName("android.os.SystemProperties");
        Method set = properties.getMethod("set", String.class, String.class);
        Method get = properties.getMethod("get", String.class);
        String[] keys = {"persist.vendor.sat.forceModeSele", "persist.vendor.sat.binningModeW",
                "vendor.debug.camera.miaec.auto_hdr_mode"};
        // Resolve and exercise read access before READY; this never changes a property.
        for (String key : keys) get.invoke(null, key);
        System.out.println("READY"); System.out.flush();
        if (!"GO".equals(new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine()))
            System.exit(3);
        for (String key : keys) set.invoke(null, key, "0");
        for (String key : keys) System.out.println(get.invoke(null, key));
        System.out.flush();
        System.exit(0);
    }
}
