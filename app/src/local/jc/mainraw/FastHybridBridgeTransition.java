package local.jc.mainraw;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** A bounded, single-use property transition. No mutation occurs before GO. */
final class FastHybridBridgeTransition implements AutoCloseable {
    static final String APPLY = "setprop persist.vendor.sat.forceModeSele 0; "
            + "setprop persist.vendor.sat.binningModeW 0; setprop vendor.debug.camera.miaec.auto_hdr_mode 0; "
            + "getprop persist.vendor.sat.forceModeSele; getprop persist.vendor.sat.binningModeW; getprop vendor.debug.camera.miaec.auto_hdr_mode";
    static final String PREPARE = "echo READY; IFS= read -r -t 5 jc_bridge_go; "
            + "[ \"$jc_bridge_go\" = GO ] || exit 3; " + APPLY;
    private final Process process;
    private final BufferedReader input;
    private boolean used;

    FastHybridBridgeTransition(Process process) throws Exception {
        this.process = process;
        input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        FutureTask<String> ready = new FutureTask<>(() -> input.readLine());
        Thread reader = new Thread(ready, "JC-Bridge-Ready"); reader.setDaemon(true); reader.start();
        try {
            if (!"READY".equals(ready.get(2, TimeUnit.SECONDS))) throw new IOException("bridge helper not ready");
        } catch (Exception e) { close(); throw e; }
    }

    String apply() throws Exception {
        if (used) throw new IOException("bridge transition already used");
        used = true;
        process.getOutputStream().write("GO\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        FutureTask<Integer> completion = new FutureTask<>(() -> process.waitFor());
        Thread waiter = new Thread(completion, "JC-Bridge-Exit"); waiter.setDaemon(true); waiter.start();
        try { completion.get(2, TimeUnit.SECONDS); }
        catch (Exception e) { close(); throw new IOException("bridge transition failed or timed out", e); }
        StringBuilder result = new StringBuilder(); String line;
        while ((line = input.readLine()) != null) {
            if (result.length() > 0) result.append('\n');
            result.append(line);
        }
        if (process.exitValue() != 0 || !result.toString().equals("0\n0\n0"))
            throw new IOException("bridge property readback rejected: " + result);
        return result.toString();
    }

    @Override public void close() { process.destroy(); }
}
