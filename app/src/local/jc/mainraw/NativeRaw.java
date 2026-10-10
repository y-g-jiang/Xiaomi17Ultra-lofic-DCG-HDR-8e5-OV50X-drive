package local.jc.mainraw;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Phone/host shared codec. No capture, temporal merge, denoising, or ISO adjustment. */
public strictfp final class NativeRaw {
    public static final int W=4096,H=3072,ISO=50;
    public static final class Metadata {
        public long timestamp,exposure;
        public int iso,mode,policy;
        public float[] gain,black;
        public int[] neutral;
    }
    private static void require(boolean ok,String why)throws IOException {if(!ok)throw new IOException(why);}
    private static ByteBuffer le(byte[] b){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}
    public static Metadata metadata(byte[] data)throws IOException {
        require(data.length>=48,"Metadata header truncated");ByteBuffer b=le(data);
        int size=b.getInt(0),n=b.getInt(12),capacity=b.getInt(16),start=b.getInt(20),used=b.getInt(24),dataStart=b.getInt(32);
        require(b.getInt(4)==1&&size<=data.length&&size>=48&&n>=0&&n<=capacity&&n<100000&&start>=48&&(long)start+16L*n<=size&&used>=0&&dataStart>=0&&(long)dataStart+used<=size,"Invalid metadata bounds");
        Map<Integer,byte[]> fields=new HashMap<>();Map<Integer,Integer> types=new HashMap<>();int[] widths={1,4,4,8,8,8};
        Set<Integer> selected=new HashSet<>(Arrays.asList(0xe0010,0xe0000,0xe0002,0xe0012,0xe001c,0x8003000c,0x80560009,0x82430002));
        for(int i=0;i<n;i++){
            int at=start+i*16,tag=b.getInt(at),count=b.getInt(at+4),offset=b.getInt(at+8),type=b.get(at+12)&255;
            require(type<6&&count>=0,"Invalid metadata entry");long length=(long)count*widths[type];
            long pos=length<=4?at+8:(long)dataStart+offset;
            require(length<=Integer.MAX_VALUE&&pos>=0&&pos+length<=size&&(length<=4||(offset>=0&&(long)offset+length<=used)),"Metadata entry out of bounds");
            if(selected.contains(tag)){require(!fields.containsKey(tag),"Duplicate required metadata tag");fields.put(tag,Arrays.copyOfRange(data,(int)pos,(int)(pos+length)));types.put(tag,type);}
        }
        Metadata m=new Metadata();m.timestamp=read(fields,types,0xe0010,3,8).getLong();m.exposure=read(fields,types,0xe0000,3,8).getLong();
        m.iso=read(fields,types,0xe0002,1,4).getInt();m.mode=read(fields,types,0x80560009,0,1).get()&255;m.policy=read(fields,types,0x82430002,1,4).getInt();
        m.gain=new float[3];ByteBuffer g=read(fields,types,0x8003000c,2,12);for(int i=0;i<3;i++)m.gain[i]=g.getFloat();
        m.black=new float[4];ByteBuffer k=read(fields,types,0xe001c,2,16);for(int i=0;i<4;i++)m.black[i]=k.getFloat();
        m.neutral=new int[6];ByteBuffer wb=read(fields,types,0xe0012,5,24);for(int i=0;i<6;i++)m.neutral[i]=wb.getInt();
        require(m.iso==ISO,"Actual ISO is not fixed minimum 50: "+m.iso);
        require(m.mode==5&&m.policy==11,"Not validated main-camera mode5/policy11");
        require(m.timestamp>0&&m.exposure>0&&m.exposure<=1_000_000_000L,"Invalid exposure/timestamp");
        for(float v:m.gain)require(Float.isFinite(v)&&Math.abs(v-1f)<1e-6,"Actual AEC gain is not 1x");
        for(float v:m.black)require(Float.isFinite(v)&&v>=1023&&v<=1026,"Unexpected RAW14 black level");
        for(int i=0;i<6;i+=2)require(m.neutral[i]>0&&m.neutral[i+1]>0,"Invalid white balance");
        return m;
    }
    private static ByteBuffer read(Map<Integer,byte[]> f,Map<Integer,Integer> t,int id,int type,int length)throws IOException {
        byte[] v=f.get(id);require(v!=null&&v.length==length&&t.get(id)==type,"Missing/wrong required metadata: "+Integer.toHexString(id));return le(v);
    }
    public static void pair(Metadata a,Metadata b)throws IOException {
        require(a.timestamp==b.timestamp&&a.exposure==b.exposure,"Branches are not the same timestamp/exposure");
        require(a.iso==ISO&&b.iso==ISO&&a.mode==5&&b.mode==5,"Pair not ISO50 mode5");
    }
    public static void unpack(byte[] packed,int bits,int[] out)throws IOException {
        require(out.length>=4&&out.length<=8192&&out.length%4==0&&packed.length==out.length*(bits==14?7:5)/4&&(bits==14||bits==10),"Wrong MIPI row length");
        for(int x=0,p=0;x<out.length;x+=4,p+=bits==14?7:5){
            if(bits==14){int v4=packed[p+4]&255,v5=packed[p+5]&255,v6=packed[p+6]&255;
                out[x]=((packed[p]&255)<<6)|(v4&63);out[x+1]=((packed[p+1]&255)<<6)|(v4>>6)|((v5&15)<<2);
                out[x+2]=((packed[p+2]&255)<<6)|(v5>>4)|((v6&3)<<4);out[x+3]=((packed[p+3]&255)<<6)|(v6>>2);
            }else for(int i=0;i<4;i++)out[x+i]=((packed[p+i]&255)<<2)|(((packed[p+4]&255)>>(2*i))&3);
        }
    }
    public static float merge(int main,int paired){
        if(main<=12000)return main;
        float mapped=1024f+120f*(paired-64f);if(main>=15000)return mapped;
        float w=(main-12000f)/3000f;w=w*w*(3f-2f*w);return main+w*(mapped-main);
    }
    /** Uniform scaling, with <=0.5 output-code rounding error. No gamma or tone curve. */
    public static int merge16(int main,int paired){return Math.round(merge(main,paired)*0.5f);}
    private static int tagPosition(byte[] header,int code,int bytes)throws IOException {
        ByteBuffer b=le(header);int ifd=b.getInt(4),n=b.getShort(ifd)&65535;
        for(int i=0;i<n;i++){int p=ifd+2+i*12;if((b.getShort(p)&65535)==code){int at=bytes<=4?p+8:b.getInt(p+8);require(at>=0&&at+bytes<=header.length,"DNG tag bounds");return at;}}
        throw new IOException("Missing template tag "+code);
    }
    public static byte[] header(byte[] template,Metadata m,int kind,String date)throws IOException {
        byte[] h=template.clone();ByteBuffer b=le(h);int p=tagPosition(h,33434,8);
        // Reduce exposure rational to avoid unsigned 32-bit overflow.
        long num=m.exposure,den=1_000_000_000L,gcd=gcd(num,den);b.putInt(p,(int)(num/gcd));b.putInt(p+4,(int)(den/gcd));
        p=tagPosition(h,50728,24);for(int v:m.neutral){b.putInt(p,v);p+=4;}
        p=tagPosition(h,50714,32);for(int i=0;i<4;i++){float black=kind==1?64f:m.black[i];if(kind==2)black*=0.5f;b.putInt(p,Math.round(black*4));b.putInt(p+4,4);p+=8;}
        require(date.matches("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"),"Invalid capture date");
        p=tagPosition(h,36867,20);byte[] d=(date+'\0').getBytes("US-ASCII");System.arraycopy(d,0,h,p,20);
        return h;
    }
    private static long gcd(long a,long b){while(b!=0){long c=a%b;a=b;b=c;}return a;}
    public static void write(File raw14,File raw10,OutputStream out,byte[] template,Metadata m,int kind,String date)throws IOException {
        require(kind>=0&&kind<=2,"Wrong output kind");require(raw14.length()==22020096L&&raw10.length()==15728640L,"Incomplete/wrong-sized packed RAW pair");
        out.write(header(template,m,kind,date));
        try(DataInputStream a=new DataInputStream(new BufferedInputStream(new FileInputStream(raw14)));DataInputStream b=new DataInputStream(new BufferedInputStream(new FileInputStream(raw10)))){
            byte[] pa=new byte[7168],pb=new byte[5120],row=new byte[W*2];int[] va=new int[W],vb=new int[W];ByteBuffer dest=le(row);
            for(int y=0;y<H;y++){a.readFully(pa);b.readFully(pb);unpack(pa,14,va);unpack(pb,10,vb);dest.clear();
                for(int x=0;x<W;x++)dest.putShort((short)(kind==2?merge16(va[x],vb[x]):kind==0?va[x]:vb[x]));out.write(row);
            }
        }
    }
    public static byte[] bytes(InputStream in,int maximum)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>maximum)throw new IOException("Input too large");b.write(buf,0,n);}return b.toByteArray();}
}
