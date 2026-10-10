package local.jc.mainraw;

import java.io.IOException;

/** Checks actual RAW_SENSOR bytes, never requests or advertised buffer formats alone. */
final class HpeRawPolicy {
    static int[] check(byte[] raw,int w,int h,int stride,int pixelStride,int mode,int actualMode,int iso,float requestedFocus,float actualFocus)throws Exception {
        if(w!=4080||h!=3072||stride!=8160||pixelStride!=2||raw.length!=stride*h)
            throw new IOException("HPE RAW geometry mismatch");
        if(actualMode!=mode||iso!=20||!PairFocusPolicy.matches(requestedFocus,actualFocus))
            throw new IOException("HPE actual mode/ISO/focus mismatch");
        int min=65535,max=0,odd=0;int limit=mode==6?16383:1023;
        for(int row=0;row<h;row++){
            boolean present=false;
            for(int at=row*stride;at<(row+1)*stride;at+=2){
                int v=(raw[at]&255)|((raw[at+1]&255)<<8);
                if(v>limit)throw new IOException("HPE RAW code outside mode range");
                min=Math.min(min,v);max=Math.max(max,v);odd+=v&1;present|=v!=0;
            }
            if(!present)throw new IOException("HPE RAW missing row "+row);
        }
        if(mode==6&&max<=1023)throw new IOException("Mode6 RAW has no verified 14bit-scale values");
        return new int[]{min,max,odd,mode==6?14:10};
    }
}
