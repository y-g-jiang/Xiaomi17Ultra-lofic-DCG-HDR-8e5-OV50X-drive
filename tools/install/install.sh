#!/system/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
[ "$(id -u)" = 0 ]
[ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ]
[ "$(getprop ro.product.device)" = nezha ]
[ "$(getprop ro.build.version.incremental)" = OS3.0.312.0.WPACNXM ]
cd "$ROOT"
sha256sum -c runtime.sha256
for marker in /data/adb/jc-lofic-photo/active /data/adb/jc-lofic-unity/armed /data/adb/jc-app-native/active; do
    [ ! -f "$marker" ] || { echo "ACTIVE_SESSION $marker"; exit 1; }
done
for lock in /data/adb/jc-pair-camera/lock /data/adb/jc-native50/lock; do
    [ ! -e "$lock" ] || { echo "ACTIVE_LOCK $lock"; exit 1; }
done
check() { [ "$(sha256sum "$1" | cut -d ' ' -f 1)" = "$2" ]; }
check /odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so 145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b
check /odm/lib64/camera/com.qti.sensormodule.nezha_semco_ovx10500u_wide_i.bin dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613
check /odm/etc/camera/camxoverridesettings.txt 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b
check /vendor/lib64/libmicamera_hal_policy.so 6078b72e4d60984a4e520caba650a6dd3454404855af2d8c581626d0d63688c2
check /odm/etc/camera/smartae_AlgoType_SE_evlist.json 779a3f911d67ece590732b36e6d8acaa904fa0ab9f4bdfa7af8fb7fe2ca2256a
check /odm/etc/camera/smartae_Arbitrator.json ebca580094768a68803c2beda806d33a9bb24113d75dff8bd91c493d4801e697
for directory in /data/adb/jc-lofic-photo/payload /data/adb/jc-lofic-unity /data/adb/jc-native50-qbayer; do
    mkdir -p "$directory"
    chmod 0700 "$directory"
done
for name in control.sh smartae_AlgoType_SE_evlist.json smartae_Arbitrator.json libmicamera_hal_policy.so; do
    cp "$ROOT/tools/photo_v5/payload/$name" "/data/adb/jc-lofic-photo/payload/$name"
done
cp "$ROOT/tools/lofic_unity/control.sh" /data/adb/jc-lofic-unity/control.sh
cp "$ROOT/app/assets/lofic_mode5_unity.so" /data/adb/jc-lofic-unity/unity.so
cp "$ROOT/tools/native50_qbayer/control.sh" /data/adb/jc-native50-qbayer/control.sh
cp "$ROOT/app/assets/pair_mode0_module.bin" /data/adb/jc-native50-qbayer/sensormodule.bin
cp "$ROOT/tools/native50_qbayer/camx.txt" /data/adb/jc-native50-qbayer/camx.txt
chmod 0700 /data/adb/jc-lofic-photo/payload/control.sh /data/adb/jc-lofic-unity/control.sh /data/adb/jc-native50-qbayer/control.sh
if [ "${1:-}" != --payload-only ]; then
    APK=${1:-$ROOT/dist/JC_Camera.apk}
    [ -f "$APK" ]
    result=$(pm install -r "$APK" 2>&1 | cat)
    printf '%s\n' "$result"
    printf '%s\n' "$result" | grep -q '^Success$'
fi
echo INSTALLED
