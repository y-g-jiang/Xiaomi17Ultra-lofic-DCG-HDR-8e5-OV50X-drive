package local.jc.mainraw;

public final class ClassicDriverEvidenceTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        String sha="c190be2a2e1958cdc7c47349ccbe56778f1e38c66a26b0304fd4245f53ea1ecc";
        String s="10727\n"+sha+"  /odm/etc/camera/camxoverridesettings.txt\n"+FastHybridSensorMetadata.CORE_SHA+"  /vendor/lib64/hw/camera.qcom.core.so\n3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233  /odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so\n10727\n";
        check(ClassicDriverEvidence.valid(s,"10727",sha));
        check(!ClassicDriverEvidence.valid(s,"10728",sha));
        check(!ClassicDriverEvidence.valid(s.substring(0,s.length()-6)+"10728\n","10727",sha));
        check(!ClassicDriverEvidence.valid(s.replace(FastHybridSensorMetadata.CORE_SHA,sha),"10727",sha));
        check(!ClassicDriverEvidence.valid(s.replace("3af9d579","4af9d579"),"10727",sha));
        check(!ClassicDriverEvidence.valid(s.replace("camxoverridesettings.txt","other.txt"),"10727",sha));
        check(!ClassicDriverEvidence.valid(s,"10727",FastHybridSensorMetadata.CORE_SHA));
        check(!ClassicDriverEvidence.valid(s,"10727;echo",sha));
        check(!ClassicDriverEvidence.valid(s.substring(0,s.length()-2),"10727",sha));
        System.out.println("Driver-generation proof rejects stale provider, changed layout and incomplete evidence");
    }
}
