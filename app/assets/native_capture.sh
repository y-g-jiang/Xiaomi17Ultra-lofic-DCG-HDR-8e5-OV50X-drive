#!/system/bin/sh
# Tested ROM only. Optional mode5 LOFIC unity gain; all mounts restore after capture.
set -eu
BASE=/data/adb/jc-app-native
PHOTO=/data/adb/jc-lofic-photo
CAM=/data/vendor/camera
SENSOR=/odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so
SENSOR_STOCK=145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b
SENSOR_UNITY=3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233
ACTION=${1:-status}
ID=${2:-}
case "$ID" in s*[!0-9]*|'') echo 'Invalid session id'; exit 1;; s*) ;; *) exit 1;; esac
D=$BASE/$ID
PROPS='vendor.debug.camera.perframeDebug vendor.debug.camera.af.debug_mode vendor.debug.camera.af.manual vendor.debug.camera.af.ctrl.lenspos vendor.debug.camera.miaec.enable vendor.debug.camera.miaec.lock_ae vendor.debug.camera.miaec.gain_short vendor.debug.camera.miaec.gain_mid vendor.debug.camera.miaec.gain_long vendor.debug.camera.miaec.shutter_short vendor.debug.camera.miaec.shutter_mid vendor.debug.camera.miaec.shutter_long persist.vendor.camera.algoengine.AllinOne.dump vendor.debug.jc.rawconvert.dump'
PROPS="$PROPS persist.vendor.camera.mivi.loglevel persist.vendor.camera.mivi.groupsEnable"
PROPS="$PROPS persist.vendor.cameraopt.loglevel"
FILES='com.xiaomi.plugin.offcamformatconvertor.so|91de903d76907021339f267b749abdba09871e687b87f6bf61a3e549566f412e|c3528eb9ef030c815cbb27ffb716cd66a6daf343a970ad7a03b006db81c8ab06
com.xiaomi.plugin.offlineformatconvertor.so|a19d6246c096df7b1d43a78bb784b9ecb1155616a2fb3a25cfd16a706e308d47|4f2f75f4d0f3ec1ee8186107f475570513d337665912865b67e4ae39726980d9'
sha() { sha256sum "$1" | cut -d ' ' -f 1; }
identity() {
 [ "$(id -u)" = 0 ] && [ "$(getprop ro.product.device)" = nezha ] && [ "$(getprop ro.build.version.incremental)" = OS3.0.312.0.WPACNXM ] || { echo 'Root or supported ROM missing'; exit 1; }
 [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ] || { echo 'Global mount namespace required'; exit 1; }
}
photo_check() {
 [ -f "$PHOTO/active" ] || { echo 'PHOTO_BASE_INACTIVE: Photo V5 temporary patch is not active'; exit 1; }
 [ "$(sha /vendor/lib64/libmicamera_hal_policy.so)" = 12fe0f679b3e3a8b2456a9657a01401851caf86e92b1c896375fd369ae80c1d2 ]
 [ "$(sha /odm/etc/camera/smartae_AlgoType_SE_evlist.json)" = 441de0a06f23d43fd7ace4a0e8f24859e21f8b38f697df6d462ba35bf44bd0b1 ]
 [ "$(sha /odm/etc/camera/smartae_Arbitrator.json)" = fdda7d6e48a3744ec3d2db1edfc41a4489622465cba98dc072054f4df32a1dda ]
}
controller_snapshot_ready() {
 # ROM buildAlgoControlExif emits cached:computed:MIMD flags, omitting an
 # entry only when all three are zero. 0=d, 1=l, 2=b. A fresh complete
 # summary and preview decisions are both necessary; preview alone is not
 # proof that an asynchronous MIMD update allows the requested recipe.
 awk -v now="$1" -v since="$2" '
 /initMimdPolicySettings data/ { update=$1 }
 /getAlgoControlExif:AlgoControlExif:/ { stamp=$1; line=$0 }
 END {
   if(!stamp || stamp<since || now-stamp>2 || stamp>now+1) { print "CONTROLLER_UNKNOWN_OR_STALE"; exit 1 }
   if(update>stamp) { print "CONTROLLER_UPDATE_PENDING"; exit 1 }
   if(line !~ /AlgoControlExif:\(.*\)AlgoControlReason:\(.*\[boardTemp:[0-9]+\].*\[batteryTemp:[0-9]+\]\)/) { print "CONTROLLER_INCOMPLETE"; exit 1 }
   states=line; sub(/^.*AlgoControlExif:\(/,"",states); sub(/\)AlgoControlReason:.*$/,"",states)
   n=split(states,entries,";")
   for(i=1;i<=n;i++) if(entries[i] ~ /^(HDR|SE|SN|SNSC):/ && entries[i] !~ /^(HDR|SE|SN|SNSC):d:d:d$/) {
     print "CONTROLLER_BLOCKED " entries[i]; exit 1
   }
   if(line ~ /NoRegisterController:/) {
     unregistered=line; sub(/^.*NoRegisterController:\(/,"",unregistered)
     if(unregistered ~ /(^|;)(HDR|SE|SN|SNSC):/) { print "CONTROLLER_NOT_REGISTERED"; exit 1 }
   }
   print "CONTROLLER_READY"
 }' "$D/policy_preview.log"
}
capture_settings_ready() {
 [ -f "$D/armed" ] && [ ! -d "$D/finishing" ] &&
 [ "$(getprop init.svc_debug_pid.vendor.camera-provider)" = "$(cat "$D/provider")" ] || return 1
 for key in $PROPS; do
  [ "$(getprop "$key")" = "$(cat "$D/expected.$key")" ] || return 1
 done
}
resume_photo() {
 nohup /system/bin/sh "$PHOTO/payload/control.sh" watch > "$PHOTO/watch.log" 2>&1 < /dev/null &
 echo $! > "$PHOTO/watch.pid"
}
policy_snapshot_ready() {
 # Three fresh preview decisions from THIS provider must all select the
 # one-frame SE recipe. Unknown, stale, or downgraded decisions block shutter.
 awk -v now="$1" -v since="$2" '
 /checker_smartae.cpp/ && /Get_Algo_Output.*SmartAE_output:/ {
   count++; good[count]=($0 ~ /algo_type:5,evlist_num:1,/); times[count]=$1;
 }
 END {
   if(count<3) exit 1;
   for(i=count-2;i<=count;i++) if(!good[i] || times[i]<since || now-times[i]>2 || times[i]>now+1) exit 1;
 }' "$D/policy_preview.log"
}
read_policy_snapshot() {
 # logcat -d alone can keep draining a busy producer indefinitely. A finite
 # tail plus a device-side deadline must finish even if the Java app freezes.
 # Never treat partial output from a killed reader as readiness evidence.
 if ! timeout -s KILL 3 logcat -d -v epoch --pid="$(cat "$D/provider")" -t 12000 'MiAlgoEngine:D' 'PowerDataCapture:D' 'ThermalSettingManage:I' '*:S' > "$D/policy_raw.pending"; then
  : > "$D/policy_preview.log"
  echo CONTROLLER_LOG_TIMEOUT > "$D/controller.state"
  return 1
 fi
 grep -E 'checker_smartae.cpp.*Get_Algo_Output.*SmartAE_output:|getAlgoControlExif:AlgoControlExif:|initMimdPolicySettings data' "$D/policy_raw.pending" > "$D/policy_preview.log" || true
}
wait_capture_policy() {
 checks=0
 while [ "$checks" -lt 8 ]; do
  checks=$((checks+1))
  [ "$(getprop init.svc_debug_pid.vendor.camera-provider)" = "$(cat "$D/provider")" ] || return 1
  read_policy_snapshot || return 1
  if controller_snapshot_ready "$(date +%s)" "$policy_since" > "$D/controller.state" &&
     policy_snapshot_ready "$(date +%s)" "$policy_since"; then return 0; fi
  sleep 1
 done
 return 1
}
wait_camera_enumeration() {
 # init reports RUNNING before CameraService has enumerated this provider.
 # Starting the stock app during that window caches a partial camera list,
 # opens physical id 2 instead of logical id 5, and disables SmartAE.
 registered=0; registration_tries=0
 while [ "$registered" -lt 2 ]; do
  registration_tries=$((registration_tries+1))
  if [ "$registration_tries" -gt 15 ]; then echo 'Camera enumeration incomplete; camera not launched'; return 1; fi
  # Drain the dump completely. Closing its pipe with head triggers a
  # recursive SIGPIPE diagnostic storm in this ROM's camera provider.
  dumpsys -t 3 media.camera > "$D/provider_dump.pending" || return 1
  sed -n '1,18p' "$D/provider_dump.pending" > "$D/provider_ready.pending"
  if grep -q '^Number of camera devices: 9$' "$D/provider_ready.pending" &&
     grep -q '^Number of normal camera devices: 2$' "$D/provider_ready.pending"; then
   registered=$((registered+1))
  else registered=0; fi
  [ "$registered" -ge 2 ] || sleep 1
 done
 cp "$D/provider_ready.pending" "$D/provider_ready.$(date +%s).txt"
}
restart() {
 p=$(cat "$PHOTO/watch.pid")
 case "$p" in ''|*[!0-9]*) return 1;; esac
 case "$(tr '\000' ' ' < /proc/$p/cmdline)" in *"$PHOTO/payload/control.sh watch"*) ;; *) return 1;; esac
 kill "$p"
 old=$(getprop init.svc_debug_pid.vendor.camera-provider)
 stop_output=$(/system/bin/su 2000 -c 'am force-stop com.android.camera')
 if ! setprop ctl.restart vendor.camera-provider; then resume_photo; return 1; fi
 tries=0
 while [ "$(getprop init.svc.vendor.camera-provider)" != running ] || [ "$(getprop init.svc_debug_pid.vendor.camera-provider)" = "$old" ]; do
  tries=$((tries+1)); if [ "$tries" -gt 12 ]; then resume_photo; return 1; fi; sleep 1
 done
 if ! wait_camera_enumeration; then resume_photo; return 1; fi
 # The ROM can prewarm a new camera process during provider enumeration.
 # Discard its cached partial capabilities only AFTER enumeration completes.
 stop_output=$(/system/bin/su 2000 -c 'am force-stop com.android.camera')
 printf '%s\n' "$(date +%s)" > "$D/camera_cache_reset.$(date +%s).txt"
 resume_photo
}
restore() {
 [ -f "$D/armed" ] || return 0
 # Close the stock camera BEFORE releasing the fixed ISO properties.
 stop_output=$(/system/bin/su 2000 -c 'am force-stop com.android.camera')
 printf '%s\n' "$FILES" | while IFS='|' read -r name original patched; do
  target=/odm/lib64/camera/preloadplugins/$name
  current=$(sha "$target")
  if [ "$current" = "$patched" ]; then
   [ "$(stat -c '%d:%i' "$target")" = "$(stat -c '%d:%i' "$D/$name")" ] || { echo 'Unrelated mount; not restoring'; exit 1; }
   umount -l "$target"
  else [ "$current" = "$original" ]; fi
  [ "$(sha "$target")" = "$original" ]
 done
 target=/odm/etc/camera/smartae_AlgoType_SE_evlist.json
 current=$(sha "$target")
 if [ "$current" = "3f89b696dfb1208176ae72652076814815c06a54096ce445b098309759cf4feb" ]; then
  [ "$(stat -c '%d:%i' "$target")" = "$(stat -c '%d:%i' "$D/recipe.json")" ] || return 1
  umount -l "$target"
 fi
 [ "$(sha "$target")" = "441de0a06f23d43fd7ace4a0e8f24859e21f8b38f697df6d462ba35bf44bd0b1" ] || return 1
 if [ -f "$D/focus_infinity.txt" ]; then
  target=/odm/etc/camera/camxoverridesettings.txt
  current=$(sha "$target")
  if [ "$current" = b8307e1823800e470532b9ccd09b606de2f0a4d929ea5610025d3c084464499c ]; then
   [ "$(stat -c '%d:%i' "$target")" = "$(stat -c '%d:%i' "$D/focus_infinity.txt")" ] || return 1
   umount -l "$target"
  fi
  [ "$(sha "$target")" = 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b ]
 fi
 if [ -f "$D/lofic_unity.so" ]; then
  if [ "$(sha "$SENSOR")" = "$SENSOR_UNITY" ]; then
   [ "$(stat -c '%d:%i' "$SENSOR")" = "$(stat -c '%d:%i' "$D/lofic_unity.so")" ] || return 1
   umount -l "$SENSOR"
  fi
  [ "$(sha "$SENSOR")" = "$SENSOR_STOCK" ] || return 1
 fi
 for key in $PROPS; do [ -f "$D/props/$key" ] && setprop "$key" "$(cat "$D/props/$key")"; done
 restart
 for key in $PROPS; do [ "$(getprop "$key")" = "$(cat "$D/props/$key")" ]; done
 rm -f "$D/armed"
 if [ "$(cat "$BASE/active" 2>/dev/null || true)" = "$ID" ]; then rm -f "$BASE/active"; fi
 date +%s > "$D/restored"
}
identity
case "$ACTION" in
prepare)
 exposure=${3:-20000000};short=${4:-1584857};focus=${5:-0};highlight=${6:-0}
 case "$highlight" in 0|1) ;; *) exit 1;; esac
 case "$focus" in 0|1) ;; *) exit 1;; esac
 if [ "$focus" = 1 ]; then
  [ "$(getprop ro.serialno)" = 8ed70a82 ] || { echo 'Infinity calibration belongs to another device'; exit 1; }
  [ "$(sha /odm/etc/camera/camxoverridesettings.txt)" = 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b ]
 fi
 case "$exposure:$short" in *[!0-9:]*) exit 1;; esac
 [ "$exposure" -ge 23821 ] && [ "$exposure" -le 1000000000 ] && [ "$short" -ge 1 ] && [ "$short" -le "$exposure" ]
 photo_check
 [ ! -e "$BASE/active" ] || { echo 'An earlier native session needs finish/recovery'; exit 1; }
 [ ! -e "$D" ] || { echo 'Session already exists'; exit 1; }
 for key in persist.vendor.camera.algoengine.chioffline.dump persist.vendor.camera.algoengine.AllinOne.dump vendor.debug.jc.rawconvert.dump; do
  value=$(getprop "$key"); [ -z "$value" ] || [ "$value" = 0 ] || { echo 'Another dump is active'; exit 1; }
 done
 source=$(dirname "$0")
 [ "$(sha "$SENSOR")" = "$SENSOR_STOCK" ] || { echo 'Unrecognized sensor library; restore earlier experiment first'; exit 1; }
 if [ "$highlight" = 1 ]; then
  [ "$(sha "$SENSOR")" = "$SENSOR_STOCK" ]
  [ "$(sha "$source/lofic_mode5_unity.so")" = "$SENSOR_UNITY" ]
 fi
 printf '%s\n' "$FILES" | while IFS='|' read -r name original patched; do
  [ "$(sha /odm/lib64/camera/preloadplugins/$name)" = "$original" ] && [ "$(sha "$source/$name")" = "$patched" ]
 done
 mkdir -p "$D/props"; chmod 0700 "$BASE" "$D" "$D/props"
 cp "$0" "$D/control.sh"; chmod 0700 "$D/control.sh"
 for key in $PROPS; do getprop "$key" > "$D/props/$key"; done
 find "$CAM" -maxdepth 1 -type f | sort > "$D/before"
 printf '%s\n' "$ID" > "$BASE/active"; touch "$D/armed"
 trap 'trap - EXIT HUP INT TERM; restore' EXIT HUP INT TERM
 if [ "$highlight" = 1 ]; then
  cp "$source/lofic_mode5_unity.so" "$D/lofic_unity.so"
  chmod 0644 "$D/lofic_unity.so"; chown 0:0 "$D/lofic_unity.so"; chcon u:object_r:vendor_file:s0 "$D/lofic_unity.so"
  mount --bind "$D/lofic_unity.so" "$SENSOR"
  [ "$(sha "$SENSOR")" = "$SENSOR_UNITY" ]
 fi
 printf '%s\n' "$FILES" | while IFS='|' read -r name original patched; do
  cp "$source/$name" "$D/$name"; chmod 0644 "$D/$name"; chown 0:0 "$D/$name"; chcon u:object_r:vendor_file:s0 "$D/$name"
  mount --bind "$D/$name" /odm/lib64/camera/preloadplugins/$name
  [ "$(sha /odm/lib64/camera/preloadplugins/$name)" = "$patched" ]
 done
 cp "$source/recipe.json" "$D/recipe.json"
 chmod 0644 "$D/recipe.json"; chown 0:0 "$D/recipe.json"; chcon u:object_r:vendor_configs_file:s0 "$D/recipe.json"
 mount --bind "$D/recipe.json" /odm/etc/camera/smartae_AlgoType_SE_evlist.json
 if [ "$focus" = 1 ]; then
  [ "$(sha "$source/focus_infinity.txt")" = b8307e1823800e470532b9ccd09b606de2f0a4d929ea5610025d3c084464499c ]
  cp "$source/focus_infinity.txt" "$D/focus_infinity.txt"
  chmod 0644 "$D/focus_infinity.txt"; chown 0:0 "$D/focus_infinity.txt"; chcon u:object_r:vendor_configs_file:s0 "$D/focus_infinity.txt"
  mount --bind "$D/focus_infinity.txt" /odm/etc/camera/camxoverridesettings.txt
  setprop vendor.debug.camera.perframeDebug 1
  setprop vendor.debug.camera.af.debug_mode 1
  setprop vendor.debug.camera.af.manual 2
  setprop vendor.debug.camera.af.ctrl.lenspos 824
 fi
 setprop vendor.debug.camera.miaec.enable 1; setprop vendor.debug.camera.miaec.lock_ae 1
 for branch in short mid long; do setprop vendor.debug.camera.miaec.gain_$branch 1; done
 setprop vendor.debug.camera.miaec.shutter_short "$short"
 setprop vendor.debug.camera.miaec.shutter_mid "$exposure"; setprop vendor.debug.camera.miaec.shutter_long "$exposure"
 setprop persist.vendor.camera.algoengine.AllinOne.dump 1; setprop vendor.debug.jc.rawconvert.dump 1
 # Scoped plugin diagnostics provide a live readiness signal, not a delay guess.
 setprop persist.vendor.camera.mivi.loglevel 0
 setprop persist.vendor.camera.mivi.groupsEnable 2
 setprop persist.vendor.cameraopt.loglevel 0
 restart
 [ "$(sha /odm/etc/camera/smartae_AlgoType_SE_evlist.json)" = 3f89b696dfb1208176ae72652076814815c06a54096ce445b098309759cf4feb ]
 [ "$(getprop vendor.debug.camera.miaec.enable)" = 1 ] && [ "$(getprop vendor.debug.camera.miaec.lock_ae)" = 1 ]
 for branch in short mid long; do [ "$(getprop vendor.debug.camera.miaec.gain_$branch)" = 1 ]; done
 [ "$(getprop vendor.debug.camera.miaec.shutter_short)" = "$short" ]
 [ "$(getprop vendor.debug.camera.miaec.shutter_mid)" = "$exposure" ] && [ "$(getprop vendor.debug.camera.miaec.shutter_long)" = "$exposure" ]
 [ "$(getprop persist.vendor.camera.algoengine.AllinOne.dump)" = 1 ] && [ "$(getprop vendor.debug.jc.rawconvert.dump)" = 1 ]
 for key in $PROPS; do getprop "$key" > "$D/expected.${key}"; done
 date +%s > "$D/started"; getprop init.svc_debug_pid.vendor.camera-provider > "$D/provider"
 nohup /system/bin/sh "$D/control.sh" watch "$ID" > "$D/watch.log" 2>&1 < /dev/null &
 echo $! > "$D/watch.pid"
 trap - EXIT HUP INT TERM
 sleep 3
 echo 'READY_ISO50'
 ;;
watch)
 deadline=$(( $(cat "$D/started") + 180 ))
 while [ -f "$D/armed" ]; do
  sleep 3; [ -f "$D/armed" ] || exit 0
  # Normal restore deliberately changes properties and provider PID. It owns
  # the session lock; a watcher must not initiate a competing finalization.
  [ ! -d "$D/finishing" ] || continue
  bad=0
  [ "$(getprop init.svc_debug_pid.vendor.camera-provider)" = "$(cat "$D/provider")" ] || bad=1
  for key in $PROPS; do [ "$(getprop "$key")" = "$(cat "$D/expected.$key")" ] || bad=1; done
  if [ "$(date +%s)" -ge "$deadline" ] || [ "$bad" = 1 ]; then
   [ -f "$D/armed" ] || exit 0
   [ ! -d "$D/finishing" ] || continue
   echo 'Expired or capture settings changed; stopping camera and recovering'
   /system/bin/sh "$D/control.sh" finish "$ID"; exit
  fi
 done
 ;;
capture)
 [ ! -f "$D/shutter_requested" ] || { echo 'Shutter already requested for this session; refusing a second exposure'; exit 1; }
 mkdir "$D/capturing" 2>/dev/null || { echo 'Capture already running'; exit 1; }
 trap 'rmdir "$D/capturing"; /system/bin/su 2000 -c "am start -n local.jc.mainraw/.NativePhotoActivity"' EXIT
 [ -f "$D/armed" ]
 # Input must run as shell UID; root UID input is ignored on this ROM.
 sleep 4
 ui=/data/local/tmp/jc-$ID.xml
 policy_cycle=0; policy_since=$(cat "$D/started")
 while :; do
 rm -f "$ui"
 ready=0; ui_tries=0
 while [ "$ui_tries" -lt 4 ]; do
  ui_tries=$((ui_tries+1))
  if /system/bin/su 2000 -c "uiautomator dump $ui" >/dev/null &&
     grep -q 'content-desc="前后置切换,后置"' "$ui" &&
     grep -q 'content-desc="1.0倍变焦"' "$ui" &&
     grep -q 'text="拍照".*selected="true"' "$ui" &&
     grep -q 'content-desc="拍摄"' "$ui"; then ready=1; break; fi
  # Long scans may lose the Activity-side launch while the old Activity is paused.
  # Re-open only from our own page, before any shutter; never dismiss unknown UI.
  if grep -q 'package="local.jc.mainraw"' "$ui"; then
   launch_output=$(/system/bin/su 2000 -c 'am start -W -n com.android.camera/.Camera')
   printf '%s\n' "$launch_output" >> "$D/camera_launch.log"
  fi
  sleep 2
 done
 [ "$ready" = 1 ] || { echo 'Camera UI not ready; no shutter was pressed'; exit 1; }
 dumpsys -t 3 media.camera > "$D/camera_dump_before_shutter.txt"
 sed -n '1,18p' "$D/camera_dump_before_shutter.txt" > "$D/camera_client_before_shutter.txt"
 if ! grep -q '(Camera ID: 5,.*Client Package Name: com.android.camera,' "$D/camera_client_before_shutter.txt"; then
  echo 'Wrong native camera route: logical camera 5 required; no shutter was pressed'; exit 1
 fi
 expected_sensor=$SENSOR_STOCK
 [ ! -f "$D/lofic_unity.so" ] || expected_sensor=$SENSOR_UNITY
 [ "$(sha "$SENSOR")" = "$expected_sensor" ] || { echo 'Sensor library changed; no shutter was pressed'; exit 1; }
 sensor_inode=$(stat -c %i "$SENSOR")
 provider_pid=$(getprop init.svc_debug_pid.vendor.camera-provider)
 grep -E " +$sensor_inode +.*ovx10500u_wide_i.so" /proc/$provider_pid/maps > "$D/sensor_library.txt" || { echo 'Expected sensor library is not loaded; no shutter was pressed'; exit 1; }
 sha256sum "$SENSOR" >> "$D/sensor_library.txt"
 cp "$ui" "$D/ui_before_shutter.xml"
 rm -f "$ui"
 if wait_capture_policy; then
  cp "$D/policy_preview.log" "$D/policy_before_shutter.log"
  cp "$D/controller.state" "$D/controller_before_shutter.txt"
  printf '%s|%s|%s\n' "$(date +%s)" "$provider_pid" "$policy_cycle" > "$D/policy.ready"
  break
 fi
 cp "$D/policy_preview.log" "$D/policy_blocked.$policy_cycle.log"
 if [ "$policy_cycle" -ge 2 ]; then
  date +%s > "$D/policy.blocked"
  echo 'NO_SHUTTER_POLICY_BLOCKED: LOFIC single-frame policy unavailable; no shutter was pressed'
  cat "$D/controller.state"
  exit 1
 fi
 policy_cycle=$((policy_cycle+1))
 # No exposure has happened. Release preview load while the system decides
 # whether the required algorithms may resume; never override its controls.
 stop_output=$(/system/bin/su 2000 -c 'am force-stop com.android.camera')
 /system/bin/su 2000 -c 'am start -n local.jc.mainraw/.NativePhotoActivity' >/dev/null
 sleep 20
 [ -f "$D/armed" ] || exit 1
 policy_since=$(date +%s)
 launch_output=$(/system/bin/su 2000 -c 'am start -W -n com.android.camera/.Camera')
 sleep 4
 done
 capture_settings_ready || { echo 'NO_SHUTTER_SETTINGS_CHANGED: session or capture settings changed; no shutter was pressed'; exit 1; }
 date +%s > "$D/shutter_requested"
 /system/bin/su 2000 -c 'input keyevent 27'
 # Wait for both branches and their logical/physical metadata. Export validates hashes and timestamps.
 tries=0
 while [ "$tries" -lt 25 ]; do
  sleep 1; tries=$((tries+1)); [ -f "$D/armed" ] || exit 1
  find "$CAM" -maxdepth 1 -type f | sort > "$D/poll"
  comm -13 "$D/before" "$D/poll" > "$D/candidates"
  n=$(grep -c 'AllinOne.*metadata.bin$' "$D/candidates" || true)
  if [ "$n" -ge 4 ]; then
   sleep 2
   result=$(sh "$D/control.sh" finish "$ID")
   printf '%s\n' "$result" > "$D/auto_manifest"
   echo CAPTURE_READY; exit 0
  fi
 done
 echo 'No complete dual-branch metadata; use recovery'; exit 1
 ;;
finish|restore)
 [ -d "$D" ] || { echo 'Session does not exist'; exit 1; }
 # The first completed manifest is immutable. Export must not rescan a
 # growing dump directory and accidentally include subsequent captures.
 if [ "$ACTION" = finish ] && [ -f "$D/manifest.complete" ]; then cat "$D/manifest"; exit 0; fi
 if mkdir "$D/finishing" 2>/dev/null; then
  trap 'rmdir "$D/finishing"' EXIT
  restore
  if [ "$ACTION" = restore ]; then echo RESTORED; exit 0; fi
  find "$CAM" -maxdepth 1 -type f | sort > "$D/after"
  # Only newly created camera dumps; older photographs never become inputs.
  comm -13 "$D/before" "$D/after" > "$D/new"
  count=$(wc -l < "$D/new"); [ "$count" -le 150 ] || { echo 'Too many files; inspect session manually'; exit 1; }
  : > "$D/manifest.pending"
  while IFS= read -r path; do
   name=${path##*/}
   case "$name" in *AllinOne*metadata.bin|*AllinOne*input_*.RAW|IMG_*FormatConvertorInstance*.raw)
    digest=$(sha "$path"); length=$(stat -c %s "$path"); printf '%s|%s|%s\n' "$digest" "$length" "$name" >> "$D/manifest.pending";;
   esac
  done < "$D/new"
  mv "$D/manifest.pending" "$D/manifest"
  touch "$D/manifest.complete"
  cat "$D/manifest"
 else echo 'Recovery already running; wait then import again'; exit 1; fi
 ;;
retry_state)
 [ -d "$D" ] && [ -f "$D/policy.blocked" ] && [ ! -f "$D/shutter_requested" ] && [ ! -d "$D/capturing" ] && [ ! -f "$D/manifest.complete" ] || { echo NOT_RETRYABLE; exit 1; }
 echo VERIFIED_NO_SHUTTER_POLICY_BLOCKED
 ;;
status)
 if [ -f "$D/armed" ]; then echo ARMED; elif [ -f "$D/restored" ]; then echo RESTORED; else echo NOT_PREPARED; fi
 ;;
evidence)
 [ -f "$D/policy.ready" ] && [ -f "$D/shutter_requested" ] && [ -f "$D/manifest.complete" ] && [ -f "$D/restored" ]
 cycles=$(cut -d '|' -f 3 "$D/policy.ready")
 case "$cycles" in 0|1|2) ;; *) exit 1;; esac
 [ "$(cat "$D/controller_before_shutter.txt")" = CONTROLLER_READY ]
 printf '{"session":"%s","policyReadyBeforeShutter":true,"controllerReadyBeforeShutter":true,"preShutterWaitCycles":%s,"shutterRequests":1,"restored":true}\n' "$ID" "$cycles"
 ;;
*) echo 'Unsupported action'; exit 1;;
esac
