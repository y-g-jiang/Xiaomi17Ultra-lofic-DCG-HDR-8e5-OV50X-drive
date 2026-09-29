package local.jc.mainraw;

import java.io.IOException;

/** Invoke the already-installed, user-authorized su; never alters root policy or SELinux. */
public final class RootProcess {
    public static final class LaunchFailure extends IOException {
        LaunchFailure(String message,Throwable cause){super(message,cause);}
    }
    private static volatile String route="尚未启动";
    public static String route(){return route;}
    static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    static String[] direct(String cmd){return new String[]{"/system/bin/su","-M","-c",cmd};}
    static String[] shell(String cmd){return new String[]{"/system/bin/sh","-c","exec /system/bin/su -M -c "+quote(cmd)};}
    public static Process start(String cmd,boolean mergeError)throws IOException {
        try{
            Process p=new ProcessBuilder(direct(cmd)).redirectErrorStream(mergeError).start();route="/system/bin/su（绝对路径）";return p;
        }catch(IOException directFailure){
            // Some Android/KernelSU combinations expose su via exec hooks. Use the system
            // shell only when Java could not launch a child; never retry a refused su command.
            try{
                Process p=new ProcessBuilder(shell(cmd)).redirectErrorStream(mergeError).start();route="/system/bin/sh → /system/bin/su";return p;
            }catch(IOException shellFailure){
                LaunchFailure failure=new LaunchFailure("系统 su 无法启动；未执行 Root 命令。绝对路径："+directFailure.getMessage()+"；系统 shell："+shellFailure.getMessage(),shellFailure);
                failure.addSuppressed(directFailure);throw failure;
            }
        }
    }
}
