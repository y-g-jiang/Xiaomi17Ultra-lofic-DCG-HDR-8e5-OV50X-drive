package local.jc.mainraw;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class HpeRawPolicyTest {
    interface Checked {void run()throws Exception;}
    static void reject(Checked call)throws Exception {try{call.run();throw new AssertionError("accepted invalid input");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception {
        byte[] raw=new byte[4080*3072*2];Arrays.fill(raw,(byte)4);
        int[] result=HpeRawPolicy.check(raw,4080,3072,8160,2,6,6,20,1f,1f);
        if(result[0]!=1028||result[3]!=14)throw new AssertionError();
        reject(()->HpeRawPolicy.check(raw,4080,3072,8160,2,6,4,20,1f,1f));
        reject(()->HpeRawPolicy.check(raw,4080,3072,8160,2,6,6,20,1f,2f));
        reject(()->HpeRawPolicy.check(raw,4080,3072,8160,2,4,4,20,1f,1f));
        Arrays.fill(raw,2500*8160,2501*8160,(byte)0);
        reject(()->HpeRawPolicy.check(raw,4080,3072,8160,2,6,6,20,1f,1f));
        Path file=Files.createTempFile("hpe-dng-", ".dng");
        try{
            byte[] tiff={73,73,42,0,8,0,0,0,1,0,29,(byte)198,4,0,1,0,0,0,(byte)255,3,0,0,0,0,0,0,42,7};
            Files.write(file,tiff);HpeDngMetadata.setContainerWhite(file.toFile(),1023,16383);
            byte[] changed=Files.readAllBytes(file);if(changed[18]!=(byte)255||changed[19]!=63||changed[26]!=42)throw new AssertionError();
            reject(()->HpeDngMetadata.setContainerWhite(file.toFile(),1023,16383));
            Files.write(file,new byte[20]);reject(()->HpeDngMetadata.setContainerWhite(file.toFile(),1023,16383));
        }finally{Files.delete(file);}
        System.out.println("HPE code range, missing rows, mode/focus and derived TIFF metadata guards passed");
    }
}
