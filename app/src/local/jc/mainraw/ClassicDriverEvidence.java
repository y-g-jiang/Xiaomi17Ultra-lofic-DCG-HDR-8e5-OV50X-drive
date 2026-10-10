package local.jc.mainraw;

/** A file proof is reusable only for the same driver provider and verified mounted layout. */
final class ClassicDriverEvidence {
    static boolean valid(String text,String pid,String configSha){
        return text!=null&&pid!=null&&pid.matches("[0-9]+")&&configSha!=null&&configSha.matches("[0-9a-f]{64}")
            &&text.startsWith(pid+"\n")&&text.endsWith(pid+"\n")
            &&text.contains(configSha+"  /odm/etc/camera/camxoverridesettings.txt\n")
            &&text.contains(FastHybridSensorMetadata.CORE_SHA+"  /vendor/lib64/hw/camera.qcom.core.so\n")
            &&text.contains("3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233  /odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so\n");
    }
}
