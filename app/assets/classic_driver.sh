#!/system/bin/sh
set -eu
set -o pipefail
ASSETS=$1
APK=$2
MARKER=$3
OWNER=$4
ID=$5
case "$OWNER" in ''|*[!0-9]*) exit 2;; esac
case "$ID" in c?????????????) ;; *) exit 2;; esac
case "$ID" in *[!c0-9]*) exit 2;; esac
BASE=/data/adb/jc-pair-camera
WORK=/data/adb/jc-classic-camera/$ID
OUT=$WORK
CAMX=/odm/etc/camera/camxoverridesettings.txt
MODULE=/odm/lib64/camera/com.qti.sensormodule.nezha_semco_ovx10500u_wide_i.bin
SENSOR=/odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so
PHOTO=/data/adb/jc-lofic-photo/payload/control.sh
INITIAL_EXPOSURE=33333333
mkdir -p "$BASE" "$WORK"
mkdir "$BASE/lock" || exit 3
printf '%s\n' "$$" > "$WORK/owner.pid"
printf '%s\n' "$OWNER" > "$WORK/app.pid"
mark() { printf '%s %s\n' "$(date +%s%3N)" "$1" >> "$WORK/timing.txt"; }
mark driver_start
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
restore_driver() {
    code=$?
    trap - EXIT TERM INT HUP
    set +e
    # The bounded capture controller owns this nested lock while it uses the provider.
    n=0
    while [ -d "$WORK/capture.lock" ]; do
        n=$((n+1))
        if [ "$n" = 300 ] && [ -f "$WORK/capture.lock/pid" ]; then
            capturepid=$(cat "$WORK/capture.lock/pid")
            tr '\000' ' ' < "/proc/$capturepid/cmdline" 2>/dev/null | grep -q 'pair_capture.sh' && kill -TERM "$capturepid" 2>/dev/null
        fi
        if [ "$n" -gt 350 ]; then echo '{"restored":false,"error":"capture_still_active"}' > "$WORK/restored.json"; exit 1; fi
        sleep .2
    done
    mark restore_begin
    stopped
    if [ "$?" != 0 ]; then echo '{"restored":false,"error":"provider_stop"}' > "$WORK/restored.json"; exit 1; fi
    unbind_owned() {
        actual=$(sha256sum "$1" | cut -d ' ' -f 1)
        expected=$(sha256sum "$2" | cut -d ' ' -f 1)
        [ -n "$actual" ] && [ "$actual" = "$expected" ] || return 1
        umount "$1"
    }
    unbind_owned "$CAMX" "$WORK/camx.txt"
    unbind_owned "$SENSOR" "$WORK/sensor.so"
    while IFS='|' read -r key value; do setprop "$key" "$value"; done < "$WORK/properties"
    if grep -q PHOTO_V5_ACTIVE "$WORK/photo-before" && [ ! -f /data/adb/jc-lofic-photo/active ]; then
        JC_PAIR_PROVIDER_HELD=1 timeout 60 sh "$PHOTO" apply 2>&1 | cat >> "$WORK/restore.log"
    fi
    setprop ctl.start vendor.camera-provider
    ok=true
    camera_ready || ok=false
    tail -3 "$WORK/photo-before" > "$WORK/photo-baseline-files"
    sh "$PHOTO" status | tail -3 > "$WORK/photo-restored-files"
    cmp -s "$WORK/photo-baseline-files" "$WORK/photo-restored-files" || ok=false
    hash_is "$CAMX" 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b || ok=false
    hash_is "$MODULE" dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613 || ok=false
    hash_is "$SENSOR" 145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b || ok=false
    while IFS='|' read -r key value; do [ "$(getprop "$key")" = "$value" ] || ok=false; done < "$WORK/properties"
    mark restore_ready
    printf '{"restored":%s,"scriptExit":%s,"scope":"stock_after_classic_exit"}\n' "$ok" "$code" > "$WORK/restored.json"
    rmdir "$BASE/lock"
    [ "$ok" = true ] || exit 1
    exit "$code"
}
trap restore_driver EXIT
trap 'exit 124' TERM INT HUP
hash_is "$CAMX" 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b
hash_is "$MODULE" dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613
hash_is "$SENSOR" 145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b
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
mount --bind "$WORK/sensor.so" "$SENSOR"
setprop vendor.debug.camera.perframeDebug 1
setprop vendor.debug.camera.af.debug_mode 0
setprop vendor.debug.camera.af.manual 0
setprop vendor.debug.camera.af.ctrl.lenspos -1
setprop vendor.debug.camera.miaec.enable 1
setprop vendor.debug.camera.miaec.lock_ae 0
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
getprop init.svc_debug_pid.vendor.camera-provider > "$WORK/provider.pid"
CLASSPATH="$APK" timeout 12 app_process /system/bin local.jc.mainraw.PairFileAuditMain 2>&1 | cat > "$WORK/file-proof.txt"
proofpid=$(cat "$WORK/provider.pid")
[ "$(head -1 "$WORK/file-proof.txt")" = "$proofpid" ] && [ "$(tail -1 "$WORK/file-proof.txt")" = "$proofpid" ]
grep -q 'b910691b3b010662faaf501544fca36a40ae206a5c66a99a1d77ea36a10789c9  /vendor/lib64/hw/camera.qcom.core.so' "$WORK/file-proof.txt"
printf '%s\n' "$MARKER" > "$WORK/marker.path"
touch "$WORK/ready"
mark driver_ready
echo READY
while [ -f "$MARKER" ] && kill -0 "$OWNER" 2>/dev/null; do sleep .2; done
