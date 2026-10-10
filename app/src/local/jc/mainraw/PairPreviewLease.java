package local.jc.mainraw;

import android.app.Activity;
import java.io.*;
import java.util.concurrent.*;

/** Temporarily release the existing debug AE lock; restore on stop or app death. */
final class PairPreviewLease {
    private final Activity host;
    private File marker;
    private Process child;
    PairPreviewLease(Activity host){this.host=host;}
    synchronized void open() throws Exception {
        if(child!=null)return;
        marker=new File(host.getFilesDir(),"preview_ae_"+android.os.Process.myPid()+"_"+System.nanoTime());
        try(FileOutputStream out=new FileOutputStream(marker)){out.write(1);}
        String key="vendor.debug.camera.miaec.lock_ae";
        String lock=RootProcess.quote(new File(host.getFilesDir(),"preview_ae_lock").getAbsolutePath());
        String readyPid=RootProcess.quote(new File(host.getFilesDir(),"preview_af_provider").getAbsolutePath());
        String apk=RootProcess.quote(host.getApplicationInfo().sourceDir);
        String cmd="lock="+lock+"; n=0; while ! mkdir \"$lock\" 2>/dev/null; do n=$((n+1)); [ \"$n\" -lt 25 ] || exit 4; sleep .1; done; old=$(getprop "+key+"); afmode=$(getprop vendor.debug.camera.af.debug_mode); afmanual=$(getprop vendor.debug.camera.af.manual); afpos=$(getprop vendor.debug.camera.af.ctrl.lenspos); perframe=$(getprop vendor.debug.camera.perframeDebug); "
                +"stopped=0; trap '[ \"$stopped\" = 0 ] || setprop ctl.start vendor.camera-provider; setprop "+key+" \"$old\"; setprop vendor.debug.camera.af.debug_mode \"$afmode\"; setprop vendor.debug.camera.af.manual \"$afmanual\"; setprop vendor.debug.camera.af.ctrl.lenspos \"$afpos\"; setprop vendor.debug.camera.perframeDebug \"$perframe\"; rmdir \"$lock\"' EXIT; "
                +"setprop vendor.debug.camera.perframeDebug true; setprop vendor.debug.camera.af.ctrl.lenspos -1; setprop vendor.debug.camera.af.debug_mode 0; setprop vendor.debug.camera.af.manual 0; setprop "+key+" 0; [ \"$(getprop "+key+")\" = 0 ] || exit 2; "
                +"pid=$(getprop init.svc_debug_pid.vendor.camera-provider); saved=$(cat "+readyPid+" 2>/dev/null); "
                +"if [ -z \"$pid\" ] || [ \"$pid\" != \"$saved\" ]; then "
                +"clients=$(dumpsys media.camera | sed '/Allowed user IDs:/q' | grep -A 1 'Active Camera Clients:'); echo \"$clients\" | grep -q '\\[\\]' || exit 5; "
                +"stopped=1; setprop ctl.stop vendor.camera-provider; n=0; while [ \"$(getprop init.svc.vendor.camera-provider)\" != stopped ]; do n=$((n+1)); [ \"$n\" -lt 50 ] || exit 6; sleep .1; done; "
                +"gone=$(CLASSPATH="+apk+" timeout 11 app_process /system/bin local.jc.mainraw.PairCameraReadyMain 0 2>&1 | cat); echo \"$gone\" | grep -q 'READY cameras=0' || exit 8; setprop ctl.start vendor.camera-provider; stopped=0; "
                +"ready=$(CLASSPATH="+apk+" timeout 11 app_process /system/bin local.jc.mainraw.PairCameraReadyMain 2>&1 | cat); echo \"$ready\" | grep -q 'READY cameras=9' || exit 7; "
                +"getprop init.svc_debug_pid.vendor.camera-provider > "+readyPid+"; fi; echo READY; "
                +"while [ -f "+RootProcess.quote(marker.getAbsolutePath())+" ] && kill -0 "+android.os.Process.myPid()+" 2>/dev/null; do sleep .2; done";
        child=RootProcess.start(cmd,true);
        final Process process=child;
        FutureTask<String> ready=new FutureTask<>(()->{BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()));String line,last="";while((line=reader.readLine())!=null){android.util.Log.i("JCPreviewLease",line);if("READY".equals(line))return line;last=line;}return last+" exit="+process.waitFor();});
        Thread t=new Thread(ready,"JC-Preview-AE");t.setDaemon(true);t.start();
        try{String answer=ready.get(29,TimeUnit.SECONDS);if(!"READY".equals(answer))throw new IOException("预览初始化失败："+answer);}
        catch(Exception e){close();throw e;}
    }
    synchronized void close() throws Exception {
        if(marker!=null && marker.exists() && !marker.delete())throw new IOException("AE lease marker removal failed");
        if(child!=null){if(!child.waitFor(4,TimeUnit.SECONDS)||child.exitValue()!=0)throw new IOException("Preview AE restoration failed");child=null;}
        marker=null;
    }
}
