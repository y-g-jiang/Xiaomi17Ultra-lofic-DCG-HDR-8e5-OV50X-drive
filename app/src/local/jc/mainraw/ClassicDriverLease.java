package local.jc.mainraw;

import android.app.Activity;
import java.io.*;
import java.util.concurrent.*;

/** Owns the classic HDR driver for the Activity lifetime; each shot borrows it. */
final class ClassicDriverLease {
    private final Activity host;
    private Process child;
    private File marker;
    private String work;
    ClassicDriverLease(Activity host){this.host=host;}
    synchronized String work()throws IOException {if(child==null||!child.isAlive())throw new IOException("HDR driver is not ready");return work;}
    synchronized void open()throws Exception {
        if(child!=null){if(child.isAlive())return;throw new IOException("HDR driver owner exited");}
        File assets=new File(host.getFilesDir(),"classic_capture");assets.mkdirs();
        for(String name:new String[]{"classic_driver.sh","pair_capture.sh","pair_camx.txt","pair_mode0_module.bin","lofic_mode5_unity.so"}){
            File target=new File(assets,name),temp=new File(assets,name+".tmp");
            try(InputStream in=host.getAssets().open(name);OutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
            android.system.Os.rename(temp.getAbsolutePath(),target.getAbsolutePath());
        }
        String id="c"+System.currentTimeMillis();work="/data/adb/jc-classic-camera/"+id;
        marker=new File(host.getFilesDir(),"classic_driver_"+id);try(OutputStream out=new FileOutputStream(marker)){out.write(1);}
        String command="sh "+RootProcess.quote(new File(assets,"classic_driver.sh").getAbsolutePath())+" "+RootProcess.quote(assets.getAbsolutePath())+" "+RootProcess.quote(host.getApplicationInfo().sourceDir)+" "+RootProcess.quote(marker.getAbsolutePath())+" "+android.os.Process.myPid()+" "+id;
        child=RootProcess.start(command,true);final Process process=child;
        FutureTask<String> ready=new FutureTask<>(()->{BufferedReader in=new BufferedReader(new InputStreamReader(process.getInputStream()));String line,last="";while((line=in.readLine())!=null){android.util.Log.i("JCClassicDriver",line);if(line.equals("READY"))return line;last=line;}return last+" exit="+process.waitFor();});
        Thread t=new Thread(ready,"JC-Classic-Driver-Ready");t.setDaemon(true);t.start();
        try{String result=ready.get(29,TimeUnit.SECONDS);if(!"READY".equals(result))throw new IOException(result);}catch(Exception e){close();throw e;}
    }
    synchronized void close()throws Exception {
        if(marker!=null&&marker.exists()&&!marker.delete())throw new IOException("Cannot release HDR driver marker");
        if(child!=null){if(!child.waitFor(29,TimeUnit.SECONDS)||child.exitValue()!=0)throw new IOException("HDR driver restoration failed");child=null;}
        marker=null;work=null;
    }
}
