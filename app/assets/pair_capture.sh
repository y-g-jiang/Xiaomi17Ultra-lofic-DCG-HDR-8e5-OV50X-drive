#!/system/bin/sh
# One shutter action: handheld pair or tripod 50MP + quick/slow HDR. Independent restoration.
set -eu
set -o pipefail
ASSETS=$1
ID=$2
EXPOSURE=$3
GAIN=$4
APK=$5
FOCUS_DISTANCE=$6
SAMPLING_MODE=${7:-handheld}
HDR_EXPOSURE=${8:-33333333}
DRIVER=${9:-}
HOT=false
if [ "$SAMPLING_MODE" = classic ] && [ -n "$DRIVER" ]; then
    case "$DRIVER" in /data/adb/jc-classic-camera/c?????????????) ;; *) exit 2;; esac
    token=${DRIVER##*/}; case "$token" in *[!c0-9]*) exit 2;; esac
    [ -f "$DRIVER/ready" ] && [ -f "$DRIVER/owner.pid" ] && kill -0 "$(cat "$DRIVER/owner.pid")"
    HOT=true
fi
RETURN_ACTIVITY=PairPhotoActivity
REVERSE=true
CLASSIC=false
FOCUS_LOG_PID=
INITIAL_EXPOSURE=1000000
DIAGNOSTIC_EXPOSURE=33333333
FRAME_NS=33333333
if [ "$SAMPLING_MODE" = classic ]; then
 RETURN_ACTIVITY=ClassicHdrActivity
 REVERSE=false
 CLASSIC=true
 INITIAL_EXPOSURE=$HDR_EXPOSURE
 DIAGNOSTIC_EXPOSURE=$HDR_EXPOSURE
 FRAME_NS=$((HDR_EXPOSURE+1000000))
 [ "$FRAME_NS" -ge 33333333 ] || FRAME_NS=33333333
fi
case "$HDR_EXPOSURE" in *[!0-9]*) exit 2;; esac
[ "$HDR_EXPOSURE" -ge 100000 ] && [ "$HDR_EXPOSURE" -le 1000000000 ]
case "$SAMPLING_MODE" in handheld) MAX_EXPOSURE=33333333;; tripod|manual|classic) MAX_EXPOSURE=1000000000;; *) exit 2;; esac
case "$FOCUS_DISTANCE" in *[!0-9.]*) exit 2;; esac
awk "BEGIN { exit !($FOCUS_DISTANCE >= 0 && $FOCUS_DISTANCE <= 100) }"
case "$ID" in p?????????????) ;; *) exit 2;; esac
case "$ID$EXPOSURE$GAIN" in *[!p0-9.]*) exit 2;; esac
[ "$EXPOSURE" -gt 0 ] && [ "$EXPOSURE" -le "$MAX_EXPOSURE" ]
awk "BEGIN { exit !($GAIN >= 1 && $GAIN <= 16) }"
[ "$SAMPLING_MODE" = handheld ] || awk "BEGIN { exit !($GAIN == 1) }"
BASE=/data/adb/jc-pair-camera
OUT=/sdcard/Android/data/local.jc.mainraw/files/Pictures/Pairs/$ID
CAMX=/odm/etc/camera/camxoverridesettings.txt
MODULE=/odm/lib64/camera/com.qti.sensormodule.nezha_semco_ovx10500u_wide_i.bin
SENSOR=/odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so
PHOTO=/data/adb/jc-lofic-photo/payload/control.sh
mkdir -p "$BASE" "$OUT"
if [ "$HOT" = true ]; then
    mkdir "$DRIVER/capture.lock" || exit 3
    printf '%s\n' "$$" > "$DRIVER/capture.lock/pid"
else mkdir "$BASE/lock" || exit 3
fi
WORK=$BASE/$ID
mkdir "$WORK"
mark() { printf '%s %s\n' "$(date +%s%3N)" "$1" >> "$OUT/timing.txt"; }
mark controller_start
CAPTURE_LOG_SINCE=$(date +%s)
# The App already released its camera. Retain its progress UI while the
# stock recovery helper restarts the provider; preserve all recovery checks.
sed -e 's/^ am force-stop local.jc.mainraw$/ : # camera released by PairPhotoActivity/' \
    -e '/^ setprop ctl.restart /s/^ / [ "${JC_PAIR_PROVIDER_HELD:-0}" = 1 ] || /' \
    -e '/^ oldpid=/i\ if [ "${JC_PAIR_PROVIDER_HELD:-0}" != 1 ]; then' \
    -e '/^ date +%s > /i\ fi' "$PHOTO" > "$WORK/photo-control.sh"
PHOTO="$WORK/photo-control.sh"
sed 's/^manualAf=2$/manualAf=0/' "$ASSETS/pair_camx.txt" > "$WORK/camx.txt"
cp "$ASSETS/pair_mode0_module.bin" "$WORK/module.bin"
cp "$ASSETS/lofic_mode5_unity.so" "$WORK/sensor.so"
PROPS='vendor.debug.camera.perframeDebug vendor.debug.camera.af.debug_mode vendor.debug.camera.af.manual vendor.debug.camera.af.ctrl.lenspos vendor.debug.camera.miaec.enable vendor.debug.camera.miaec.lock_ae vendor.debug.camera.miaec.gain_short vendor.debug.camera.miaec.gain_mid vendor.debug.camera.miaec.gain_long vendor.debug.camera.miaec.shutter_short vendor.debug.camera.miaec.shutter_mid vendor.debug.camera.miaec.shutter_long vendor.debug.camera.miaec.auto_hdr_mode persist.vendor.sat.forceModeSele persist.vendor.sat.binningModeW'
for key in $PROPS; do printf '%s|%s\n' "$key" "$(getprop "$key")"; done > "$WORK/properties"
sh "$PHOTO" status > "$WORK/photo-before" 2>&1 || true
hash_is() { [ "$(sha256sum "$1" | cut -d ' ' -f 1)" = "$2" ]; }
stopped() {
    setprop ctl.stop vendor.camera-provider
    n=0
    while [ "$(getprop init.svc.vendor.camera-provider)" != stopped ]; do n=$((n+1)); [ "$n" -lt 50 ] || return 1; sleep .1; done
}
camera_ready() {
    [ -f "$APK" ] || return 1
    CLASSPATH="$APK" timeout 12 app_process /system/bin local.jc.mainraw.PairCameraReadyMain 2>&1 | cat > "$WORK/ready.txt"
    grep -q '^READY cameras=9$' "$WORK/ready.txt"
}
restore() {
    code=$?
    mark restore_begin
    [ -z "$FOCUS_LOG_PID" ] || kill "$FOCUS_LOG_PID" 2>/dev/null || true
    trap - EXIT TERM INT HUP
    set +e
    if [ "$code" != 0 ]; then /system/bin/su 2000 -c 'am force-stop local.jc.mainraw' 2>&1 | cat; fi
    if [ "$HOT" = true ]; then
        while IFS='|' read -r key value; do setprop "$key" "$value"; done < "$WORK/properties"
        ok=true
        hash_is "$CAMX" "$(sha256sum "$DRIVER/camx.txt" | cut -d ' ' -f 1)" || ok=false
        hash_is "$SENSOR" 3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233 || ok=false
        hash_is "$MODULE" dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613 || ok=false
        [ "$(getprop init.svc_debug_pid.vendor.camera-provider)" = "$(cat "$DRIVER/provider.pid")" ] || ok=false
        while IFS='|' read -r key value; do [ "$(getprop "$key")" = "$value" ] || ok=false; done < "$WORK/properties"
        (set +o pipefail; dumpsys media.camera | sed '/Allowed user IDs:/q') > "$WORK/camera-after.txt"
        grep -A 1 'Active Camera Clients:' "$WORK/camera-after.txt" | grep -q '\[\]' || ok=false
        printf '{"restored":%s,"scriptExit":%s,"scope":"classic_driver_lease","stockRestored":false,"driverLeaseWork":"%s","providerPid":"%s"}\n' "$ok" "$code" "$DRIVER" "$(cat "$DRIVER/provider.pid")" > "$OUT/restored.json.tmp"
        mv "$OUT/restored.json.tmp" "$OUT/restored.json"
        rm "$DRIVER/capture.lock/pid"
        rmdir "$DRIVER/capture.lock"
        mark preview_properties_restored
        # If a failed controller stopped the App, let its driver owner restore stock first.
        if ! kill -0 "$(cat "$DRIVER/app.pid")" 2>/dev/null; then
            n=0; while kill -0 "$(cat "$DRIVER/owner.pid")" 2>/dev/null; do n=$((n+1)); [ "$n" -lt 100 ] || exit 1; sleep .1; done
        fi
        mark return_ui
        /system/bin/su 2000 -c "am start --activity-clear-top -n local.jc.mainraw/.$RETURN_ACTIVITY --es pairSessionId $ID" 2>&1 | cat > /dev/null
        exit "$code"
    fi
    stopped
    if [ "$?" != 0 ]; then echo '{"restored":false,"error":"provider_stop"}' > "$OUT/restored.json"; exit 1; fi
    unbind_owned() {
        if [ "$(sha256sum "$1" | cut -d ' ' -f 1)" = "$(sha256sum "$2" | cut -d ' ' -f 1)" ]; then umount "$1"; fi
    }
    unbind_owned "$CAMX" "$WORK/camx.txt"
    unbind_owned "$MODULE" "$WORK/module.bin"
    unbind_owned "$SENSOR" "$WORK/sensor.so"
    while IFS='|' read -r key value; do setprop "$key" "$value"; done < "$WORK/properties"
    if grep -q PHOTO_V5_ACTIVE "$WORK/photo-before" && [ ! -f /data/adb/jc-lofic-photo/active ]; then
        JC_PAIR_PROVIDER_HELD=1 timeout 60 sh "$PHOTO" apply 2>&1 | cat >> "$WORK/restore.log"
    fi
    mark files_restored
    setprop ctl.start vendor.camera-provider
    ok=true
    tail -3 "$WORK/photo-before" > "$WORK/photo-baseline-files"
    sh "$PHOTO" status | tail -3 > "$WORK/photo-restored-files"
    cmp -s "$WORK/photo-baseline-files" "$WORK/photo-restored-files" || ok=false
    hash_is "$CAMX" 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b || ok=false
    hash_is "$MODULE" dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613 || ok=false
    hash_is "$SENSOR" 145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b || ok=false
    while IFS='|' read -r key value; do [ "$(getprop "$key")" = "$value" ] || ok=false; done < "$WORK/properties"
    camera_ready || ok=false
    [ "$(getprop init.svc.vendor.camera-provider)" = running ] || ok=false
    mark restore_ready
    (set +o pipefail; dumpsys media.camera | sed '/Allowed user IDs:/q') > "$WORK/camera-after.txt"
    grep -A 1 'Active Camera Clients:' "$WORK/camera-after.txt" | grep -q '\[\]' || ok=false
    printf '{"restored":%s,"scriptExit":%s}\n' "$ok" "$code" > "$OUT/restored.json.tmp"
    mv "$OUT/restored.json.tmp" "$OUT/restored.json"
    cp "$WORK/run.log" "$OUT/phone-run.log" 2>/dev/null
    rmdir "$BASE/lock"
    mark return_ui
    /system/bin/su 2000 -c "am start --activity-clear-top -n local.jc.mainraw/.$RETURN_ACTIVITY --es pairSessionId $ID" 2>&1 | cat > /dev/null
    exit "$code"
}
trap restore EXIT
trap 'exit 124' TERM INT HUP
if [ "$HOT" = true ]; then
    hash_is "$CAMX" "$(sha256sum "$DRIVER/camx.txt" | cut -d ' ' -f 1)"
    hash_is "$SENSOR" 3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233
    hash_is "$MODULE" dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613
    [ "$(getprop init.svc_debug_pid.vendor.camera-provider)" = "$(cat "$DRIVER/provider.pid")" ]
    setprop vendor.debug.camera.miaec.lock_ae 1
    setprop vendor.debug.camera.af.ctrl.lenspos 0
    for part in short mid long; do setprop vendor.debug.camera.miaec.gain_$part 1; setprop vendor.debug.camera.miaec.shutter_$part "$INITIAL_EXPOSURE"; done
    mark persistent_driver_reused
else
hash_is "$CAMX" 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b
hash_is "$MODULE" dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613
hash_is "$SENSOR" 145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b
hash_is "$WORK/module.bin" 32a715889eb54d601ade4a58cd76ff640b9c89631a2ab6f33fc7c1495cbb200d
hash_is "$WORK/sensor.so" 3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233
/system/bin/su 2000 -c 'am force-stop com.android.camera' 2>&1 | cat
stopped
if grep -q PHOTO_V5_ACTIVE "$WORK/photo-before"; then
    JC_PAIR_PROVIDER_HELD=1 timeout 60 sh "$PHOTO" revert 2>&1 | cat > "$WORK/revert.log" || exit 4
fi
mark baseline_reverted
chmod 0644 "$WORK/camx.txt" "$WORK/module.bin" "$WORK/sensor.so"
chcon u:object_r:vendor_configs_file:s0 "$WORK/camx.txt"
chcon u:object_r:vendor_file:s0 "$WORK/module.bin" "$WORK/sensor.so"
mount --bind "$WORK/camx.txt" "$CAMX"
[ "$CLASSIC" = true ] || mount --bind "$WORK/module.bin" "$MODULE"
mount --bind "$WORK/sensor.so" "$SENSOR"
setprop vendor.debug.camera.perframeDebug 1
setprop vendor.debug.camera.af.debug_mode 0
setprop vendor.debug.camera.af.manual 0
setprop vendor.debug.camera.af.ctrl.lenspos 0
setprop vendor.debug.camera.miaec.enable 1
setprop vendor.debug.camera.miaec.lock_ae 1
for part in short mid long; do
    setprop vendor.debug.camera.miaec.gain_$part 1
    setprop vendor.debug.camera.miaec.shutter_$part "$INITIAL_EXPOSURE"
done
setprop vendor.debug.camera.miaec.auto_hdr_mode 8
setprop persist.vendor.sat.forceModeSele 1
setprop persist.vendor.sat.binningModeW 11
setprop ctl.start vendor.camera-provider
n=0
while [ "$(getprop init.svc.vendor.camera-provider)" != running ]; do n=$((n+1)); [ "$n" -lt 100 ]; sleep .1; done
camera_ready
mark capture_ready
fi
FOCUS_SHA=$(sha256sum "$CAMX" | cut -d ' ' -f 1)
FOCUS_PID=$(getprop init.svc_debug_pid.vendor.camera-provider)
DRIVER_PROOF_EXTRA=
if [ "$HOT" = true ]; then
    cp "$DRIVER/file-proof.txt" "$OUT/driver-file-proof.txt"
    PROOF_SHA=$(sha256sum "$OUT/driver-file-proof.txt" | cut -d ' ' -f1)
    DRIVER_PROOF_EXTRA="--es classicDriverProofSha $PROOF_SHA"
fi
if [ "$CLASSIC" = true ]; then
    grep -E '^(manualAf|lensPos)=' "$CAMX" > "$OUT/focus-live.txt"
    logcat -b main -v epoch --pid="$FOCUS_PID" -T 1 -e 'Actuator library loaded|Infinity:|ManualAF Override|TargetPosition' >> "$OUT/focus-live.txt" 2>&1 &
    FOCUS_LOG_PID=$!
    mark focus_logger_started
fi
/system/bin/su 2000 -c "am start -W -n local.jc.mainraw/.FastHybridActivity \
 --ez autoLofic true --es cameraId 0 --es physicalId 2 --ei operation 0 \
 --el rawUsage 1048579 --ei mp50SessionType 0 --ez mp50AuxStreams true --ez mp50GpuAux true --ez mp50YuvAux false \
 --ez snapshotDownscale true --ez abortAfterRaw false --ez loficPreview false --ei loficSessionType 36866 \
 --ez nativeRawSurfaceProbe true --ez hdrMp50Bridge true --ez mp50HdrBridge $REVERSE --ez classicHdr $CLASSIC --ez bridgeFast true --ez bridgeFastJava true \
 --ez loficPhotoModule true --ez loficCompanionProbe true --ez physicalRequestSettings true --ez ownClientName true \
 --el sensorExposureNs $DIAGNOSTIC_EXPOSURE --el sensorFrameDurationNs $FRAME_NS --ez appliedSensorMetadata true --ei loficRawFormat 324 \
 --ez rawWarmup true --ez rawPreviewIntent false --ei loficDcgWord 658949 --ei loficExposureCount 2 \
 --ez factoryInfinity true --ez previewOnly true --ez allowMp50UnityIso70 true --ez nativeRawOnlyTargets true \
 $DRIVER_PROOF_EXTRA --ef pairFocusDistance $FOCUS_DISTANCE --es focusConfigSha $FOCUS_SHA --es focusProviderPid $FOCUS_PID --es pairSessionId $ID --el pairExposureNs $EXPOSURE --es pairGain $GAIN --es samplingMode $SAMPLING_MODE --el pairHdrExposureNs $HDR_EXPOSURE" 2>&1 | cat > "$WORK/activity.log"
n=0
while [ ! -f "$OUT/complete.json" ] && [ ! -f "$OUT/failed.json" ]; do
    n=$((n+1)); [ "$n" -lt 450 ] || { echo '{"error":"capture_timeout_no_retry"}' > "$OUT/failed.json"; exit 5; }
    sleep .1
done
mark capture_complete
if [ "$HOT" = true ]; then logcat -d -v threadtime --pid="$FOCUS_PID" -T "$CAPTURE_LOG_SINCE" > "$OUT/capture-log.txt"
else logcat -d -v threadtime > "$OUT/capture-log.txt"; fi
mark logs_saved
[ -f "$OUT/complete.json" ] || exit 6
