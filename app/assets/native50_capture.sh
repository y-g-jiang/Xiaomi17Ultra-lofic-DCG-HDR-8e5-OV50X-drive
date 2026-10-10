#!/system/bin/sh
set -eu
set -o pipefail
ASSETS=$1 ID=$2 EXPOSURE=$3 FOCUS=$4 APK=$5 NOT_BEFORE=$6
case "$ID" in n?????????????) ;; *) exit 2;; esac
case "$ID$EXPOSURE$FOCUS" in *[!n0-9.]*) exit 2;; esac
case "$NOT_BEFORE" in *[!0-9]*) exit 2;; esac
[ "$EXPOSURE" -ge 100000 ] && [ "$EXPOSURE" -le 1000000000 ]
awk "BEGIN { exit !($FOCUS >= 0 && $FOCUS <= 100) }"
BASE=/data/adb/jc-native50
OUT=/sdcard/Android/data/local.jc.mainraw/files/Pictures/Native50/$ID
CAMX=/odm/etc/camera/camxoverridesettings.txt
MODULE=/odm/lib64/camera/com.qti.sensormodule.nezha_semco_ovx10500u_wide_i.bin
SENSOR=/odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so
mkdir -p "$BASE" "$OUT"
mkdir "$BASE/lock" || exit 3
WORK=$BASE/$ID
mkdir "$WORK"
mark() { printf '%s %s\n' "$(date +%s%3N)" "$1" >> "$OUT/timing.txt"; }
hash_is() { [ "$(sha256sum "$1" | cut -d ' ' -f 1)" = "$2" ]; }
PROPS='vendor.debug.camera.perframeDebug vendor.debug.camera.af.debug_mode vendor.debug.camera.af.manual vendor.debug.camera.af.ctrl.lenspos vendor.debug.camera.miaec.enable vendor.debug.camera.miaec.lock_ae vendor.debug.camera.miaec.gain_short vendor.debug.camera.miaec.gain_mid vendor.debug.camera.miaec.gain_long vendor.debug.camera.miaec.shutter_short vendor.debug.camera.miaec.shutter_mid vendor.debug.camera.miaec.shutter_long vendor.debug.camera.miaec.auto_hdr_mode persist.vendor.sat.forceModeSele persist.vendor.sat.binningModeW'
for key in $PROPS; do printf '%s|%s\n' "$key" "$(getprop "$key")"; done > "$WORK/properties"
cp "$MODULE" "$WORK/stock_module.bin"
sed 's/^manualAf=2$/manualAf=0/' "$ASSETS/pair_camx.txt" > "$WORK/official_camx.txt"
sed 's/^enableSensorRemosaic=1$/enableSensorRemosaic=0/' "$WORK/official_camx.txt" > "$WORK/qbayer_camx.txt"
echo noRemosaicCaptureMode=1 >> "$WORK/qbayer_camx.txt"
cp "$ASSETS/pair_mode0_module.bin" "$WORK/quad_module.bin"
OWN_CAMX= OWN_MODULE=
stopped() {
    setprop ctl.stop vendor.camera-provider
    n=0; while [ "$(getprop init.svc.vendor.camera-provider)" != stopped ]; do n=$((n+1)); [ "$n" -lt 50 ] || return 1; sleep .1; done
}
ready() { CLASSPATH="$APK" timeout 12 app_process /system/bin local.jc.mainraw.PairCameraReadyMain 2>&1 | cat > "$WORK/ready.txt"; grep -q '^READY cameras=9$' "$WORK/ready.txt"; }
unbind() {
    [ -z "$OWN_CAMX" ] || { hash_is "$CAMX" "$(sha256sum "$OWN_CAMX" | cut -d ' ' -f 1)" && umount "$CAMX"; } || return 1
    [ -z "$OWN_MODULE" ] || { hash_is "$MODULE" "$(sha256sum "$OWN_MODULE" | cut -d ' ' -f 1)" && umount "$MODULE"; } || return 1
    OWN_CAMX= OWN_MODULE=
}
restore() {
    code=$?; trap - EXIT TERM INT HUP; set +e; mark restore_begin
    if [ "$code" != 0 ]; then /system/bin/su 2000 -c 'am force-stop local.jc.mainraw' 2>&1 | cat; fi
    ok=true; stopped || ok=false
    unbind || ok=false
    while IFS='|' read -r key value; do setprop "$key" "$value"; done < "$WORK/properties"
    setprop ctl.start vendor.camera-provider; ready || ok=false
    hash_is "$CAMX" 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b || ok=false
    hash_is "$MODULE" dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613 || ok=false
    hash_is "$SENSOR" 145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b || ok=false
    while IFS='|' read -r key value; do [ "$(getprop "$key")" = "$value" ] || ok=false; done < "$WORK/properties"
    printf '{"restored":%s,"scriptExit":%s}\n' "$ok" "$code" > "$OUT/restored.json.tmp"; mv "$OUT/restored.json.tmp" "$OUT/restored.json"
    if [ "$code" != 0 ]; then printf '{"stage":"controller","scriptExit":%s,"automaticRetry":false}\n' "$code" > "$OUT/failed.json"; fi
    rmdir "$BASE/lock"; mark return_ui
    [ -f "$OUT/cancel.json" ] || /system/bin/su 2000 -c "am start --activity-clear-top -n local.jc.mainraw/.Native50Activity --es native50Returned $ID" 2>&1 | cat >/dev/null
    exit "$code"
}
trap restore EXIT
trap 'exit 124' TERM INT HUP
hash_is "$CAMX" 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b
hash_is "$MODULE" dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613
hash_is "$SENSOR" 145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b
hash_is "$WORK/quad_module.bin" 32a715889eb54d601ade4a58cd76ff640b9c89631a2ab6f33fc7c1495cbb200d
# This raw-only route uses the stock policy; no PhotoV5 or LOFIC patch is needed.
[ ! -f /data/adb/jc-lofic-photo/active ] || exit 4
/system/bin/su 2000 -c 'am force-stop com.android.camera' 2>&1 | cat >/dev/null
for layout in official qbayer; do
    [ ! -f "$OUT/cancel.json" ] || exit 7
    mark "prepare_$layout"; stopped; unbind
    if [ "$layout" = official ]; then cfg="$WORK/official_camx.txt"; mod="$WORK/stock_module.bin"; else cfg="$WORK/qbayer_camx.txt"; mod="$WORK/quad_module.bin"; fi
    chmod 0644 "$cfg" "$mod"; chcon u:object_r:vendor_configs_file:s0 "$cfg"; chcon u:object_r:vendor_file:s0 "$mod"
    mount --bind "$cfg" "$CAMX"; OWN_CAMX=$cfg
    if [ "$layout" = qbayer ]; then mount --bind "$mod" "$MODULE"; OWN_MODULE=$mod; fi
    setprop vendor.debug.camera.perframeDebug true
    setprop vendor.debug.camera.af.debug_mode 0
    setprop vendor.debug.camera.af.manual 0
    setprop vendor.debug.camera.af.ctrl.lenspos -1
    setprop vendor.debug.camera.miaec.enable 1
    setprop vendor.debug.camera.miaec.lock_ae 1
    setprop vendor.debug.camera.miaec.auto_hdr_mode 0
    setprop persist.vendor.sat.forceModeSele 0
    setprop persist.vendor.sat.binningModeW 0
    for part in short mid long; do setprop vendor.debug.camera.miaec.gain_$part 1; setprop vendor.debug.camera.miaec.shutter_$part "$EXPOSURE"; done
    setprop ctl.start vendor.camera-provider; ready
    [ ! -f "$OUT/cancel.json" ] || exit 7
    touch "$OUT/handoff"
    SHA=$(sha256sum "$CAMX" | cut -d ' ' -f 1); PID=$(getprop init.svc_debug_pid.vendor.camera-provider)
    /system/bin/su 2000 -c "am start -W -n local.jc.mainraw/.FastHybridActivity --ez autoMp50 true --es cameraId 0 --es physicalId 2 --ei operation 0 --ei rawFormat 37 --el rawUsage 1048579 --ei mp50SessionType 36866 --ez mp50AuxStreams true --ez mp50GpuAux true --ez mp50YuvAux false --ez mp50PreviewProbe true --ez nativeSnapshotProbe true --ez previewOnly true --ez mp50FullsizeRoute true --ez mp50MasterCallback true --el mp50StreamUseCase 524548 --ez physicalRequestSettings true --ez ownClientName true --ez allowMp50UnityIso70 true --ez rawWarmup true --el sensorExposureNs $EXPOSURE --el sensorFrameDurationNs 33333333 --ez appliedSensorMetadata true --ef pairFocusDistance $FOCUS --es focusConfigSha $SHA --es focusProviderPid $PID --es native50Id $ID --es native50Layout $layout --el native50NotBeforeMs $NOT_BEFORE" 2>&1 | cat > "$WORK/$layout-start.txt"
    n=0; while [ ! -f "$OUT/$layout/complete.json" ] && [ ! -f "$OUT/$layout/failed.json" ]; do [ ! -f "$OUT/cancel.json" ] || exit 7; n=$((n+1)); [ "$n" -lt 400 ] || exit 5; sleep .1; done
    logcat -d -b main -v epoch --pid="$PID" -e 'TargetPosition|Mode\[|exposureTime|Sensor library' > "$OUT/$layout/actuator.log"
    [ -f "$OUT/$layout/complete.json" ] || exit 6
    mark "saved_$layout"
    sleep .3
done
CLASSPATH="$APK" timeout 12 app_process /system/bin local.jc.mainraw.Native50FinalizeMain "$OUT" 2>&1 | cat > "$WORK/finalize.txt"
grep -q '^PAIR_VALID$' "$WORK/finalize.txt"
