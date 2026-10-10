package local.jc.mainraw;
import java.nio.*;
import java.nio.file.*;

public final class FastHybridSensorMetadataTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static void rejects(byte[] b) { boolean rejected=false; try { new FastHybridSensorMetadata(b); }
        catch (IllegalArgumentException e) { rejected=true; } check(rejected); }
    public static void main(String[] args) throws Exception {
        byte[] real=Files.readAllBytes(Paths.get(args[0]));
        FastHybridSensorMetadata a=new FastHybridSensorMetadata(real);
        check(a.exposureNs[0]==981805 && a.exposureNs[1]==0 && a.exposureNs[2]==981805);
        check(a.isEqualExposureUnityMode5(5,100,33289325));
        check(!a.isEqualExposureUnityMode5(0,100,33289325));
        check(!a.isEqualExposureUnityMode5(5,101,33289325));
        check(!a.isEqualExposureUnityMode5(5,100,981804));
        rejects(null); rejects(new byte[79]); rejects(new byte[81]);
        for (int offset:new int[]{0,4,8,48,52,56}) {
            byte[] b=real.clone(); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset,1.015625f);
            check(!new FastHybridSensorMetadata(b).isEqualExposureUnityMode5(5,100,33289325));
        }
        byte[] rounded=real.clone();ByteBuffer.wrap(rounded).order(ByteOrder.LITTLE_ENDIAN).putLong(64,981804);
        check(new FastHybridSensorMetadata(rounded).isEqualExposureUnityMode5(5,100,33289325,1));
        check(!new FastHybridSensorMetadata(rounded).isEqualExposureUnityMode5(5,100,33289325));
        ByteBuffer.wrap(rounded).order(ByteOrder.LITTLE_ENDIAN).putLong(64,981803);
        check(!new FastHybridSensorMetadata(rounded).isEqualExposureUnityMode5(5,100,33289325,1));
        byte[] longExposure=real.clone();ByteBuffer lb=ByteBuffer.wrap(longExposure).order(ByteOrder.LITTLE_ENDIAN);
        lb.putLong(16,499984185).putLong(64,499984192);
        FastHybridSensorMetadata longMeta=new FastHybridSensorMetadata(longExposure);
        check(longMeta.isEqualExposureUnityMode5(5,100,500000000,30));
        check(!longMeta.isEqualExposureUnityMode5(5,100,500000000,1));
        check(!longMeta.isEqualExposureUnityMode5(5,100,500000000));
        lb.putLong(64,499984216);
        check(!new FastHybridSensorMetadata(longExposure).isEqualExposureUnityMode5(5,100,500000000,30));
        check(!longMeta.isEqualExposureUnityMode5(5,100,500000000,61));
        for (float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1f}) {
            byte[] b=real.clone(); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putFloat(0,invalid); rejects(b);
        }
        for (int offset:new int[]{16,64}) {
            byte[] b=real.clone(); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(offset,981806);
            check(!new FastHybridSensorMetadata(b).isEqualExposureUnityMode5(5,100,33289325));
        }
        check(FastHybridSensorMetadata.matches(7,7,7,0,0,.05,true,true,true));
        check(!FastHybridSensorMetadata.matches(7,7,8,0,0,.05,true,true,true));
        check(!FastHybridSensorMetadata.matches(7,8,7,0,0,.05,true,true,true));
        check(!FastHybridSensorMetadata.matches(0,0,0,0,0,.05,true,true,true));
        check(!FastHybridSensorMetadata.matches(7,7,7,0,0,.05,true,true,false));
        check(!FastHybridSensorMetadata.matches(7,7,7,51,1000000,.05,true,true,true));
        check(!FastHybridSensorMetadata.matches(7,7,7,0,0,.05,true,false,true));
        check(!FastHybridSensorMetadata.matches(7,7,7,0,0,.05,false,true,true));
        System.out.println("PASS "+checks+" applied sensor metadata checks; real driver fixture replayed");
    }
}
