package local.jc.mainraw;

import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Method;

/** Read-only root helper: enumerate cameras without producing a full HAL dump. */
public final class PairCameraReadyMain {
    public static void main(String[] args) throws Exception {
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"media.camera");
        Class<?> api=Class.forName("android.hardware.ICameraService");
        Object service=Class.forName("android.hardware.ICameraService$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        Method count=null;
        for(Method m:api.getMethods())if(m.getName().equals("getNumberOfCameras"))count=m;
        if(count==null)throw new IllegalStateException("Camera enumeration unavailable");
        Class<?>[] types=count.getParameterTypes();Object[] values=new Object[types.length];
        for(int i=0;i<types.length;i++){
            if(types[i]==int.class)values[i]=i==0?1:0;
            else if(types[i].getName().equals("android.content.AttributionSourceState")){
                Object attribution=new android.content.AttributionSource.Builder(Process.myUid()).build();
                Object source=attribution.getClass().getMethod("asState").invoke(attribution);
                types[i].getField("uid").setInt(source,Process.myUid());
                types[i].getField("pid").setInt(source,Process.myPid());
                values[i]=source;
            }else throw new IllegalStateException("Unsupported camera enumeration signature");
        }
        int expected=args.length==0?9:Integer.parseInt(args[0]);
        long end=SystemClock.elapsedRealtime()+10000;
        do {
            if(((Integer)count.invoke(service,values))==expected){System.out.println("READY cameras="+expected);return;}
            Thread.sleep(100);
        }while(SystemClock.elapsedRealtime()<end);
        throw new IllegalStateException("Camera enumeration timed out");
    }
}
