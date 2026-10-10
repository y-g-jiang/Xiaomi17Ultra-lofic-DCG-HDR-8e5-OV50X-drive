package local.jc.mainraw;
import java.io.IOException;
public class PairRawPreviewTest {
    public static void main(String[] args)throws Exception {
        for(int width:new int[]{4,4096,8192})for(int bits:new int[]{10,14}) {
            int[] values=new int[width],decoded=new int[width];byte[] bytes=new byte[width*bits/8];
            for(int x=0;x<width;x++)values[x]=(x*61+17)&((1<<bits)-1);
            for(int x=0,p=0;x<width;x+=4,p+=bits==10?5:7){
                if(bits==10){for(int i=0;i<4;i++){bytes[p+i]=(byte)(values[x+i]>>2);bytes[p+4]|=(byte)((values[x+i]&3)<<(2*i));}}
                else {for(int i=0;i<4;i++)bytes[p+i]=(byte)(values[x+i]>>6);
                    int low=(values[x]&63)|((values[x+1]&63)<<6)|((values[x+2]&63)<<12)|((values[x+3]&63)<<18);
                    bytes[p+4]=(byte)low;bytes[p+5]=(byte)(low>>8);bytes[p+6]=(byte)(low>>16);
                }
            }
            NativeRaw.unpack(bytes,bits,decoded);
            if(!java.util.Arrays.equals(values,decoded))throw new AssertionError("Packed row mismatch");
            try{NativeRaw.unpack(java.util.Arrays.copyOf(bytes,bytes.length-1),bits,decoded);throw new AssertionError("Accepted truncation");}catch(IOException expected){}
        }
        System.out.println("RAW10/14 variable-width and truncation checks passed");
    }
}
