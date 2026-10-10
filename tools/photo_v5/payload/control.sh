#!/system/bin/sh
# Temporary, hash-locked reproduction. No remount-rw, SELinux changes, or boot scripts.
set -eu
BASE=/data/adb/jc-lofic-photo
SERVICE=vendor.camera-provider
ACTION=${1:-status}
FILES='smartae_AlgoType_SE_evlist.json|/odm/etc/camera/smartae_AlgoType_SE_evlist.json|779a3f911d67ece590732b36e6d8acaa904fa0ab9f4bdfa7af8fb7fe2ca2256a|441de0a06f23d43fd7ace4a0e8f24859e21f8b38f697df6d462ba35bf44bd0b1|u:object_r:vendor_configs_file:s0
smartae_Arbitrator.json|/odm/etc/camera/smartae_Arbitrator.json|ebca580094768a68803c2beda806d33a9bb24113d75dff8bd91c493d4801e697|fdda7d6e48a3744ec3d2db1edfc41a4489622465cba98dc072054f4df32a1dda|u:object_r:vendor_configs_file:s0
libmicamera_hal_policy.so|/vendor/lib64/libmicamera_hal_policy.so|6078b72e4d60984a4e520caba650a6dd3454404855af2d8c581626d0d63688c2|12fe0f679b3e3a8b2456a9657a01401851caf86e92b1c896375fd369ae80c1d2|u:object_r:vendor_file:s0'
jc_digest() { sha256sum "$1" | cut -d ' ' -f 1; }
each() { printf '%s\n' "$FILES"; }
identity() {
 [ "$(id -u)" = 0 ] || { echo 'Root required'; exit 1; }
 [ "$(getprop ro.product.device)" = nezha ] || { echo 'Wrong device'; exit 1; }
 [ "$(getprop ro.build.version.incremental)" = OS3.0.312.0.WPACNXM ] || { echo 'Wrong ROM'; exit 1; }
 [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ] || { echo 'Not in init mount namespace'; exit 1; }
}
restore_files() {
 each | while IFS='|' read -r name target original trial context; do
   current=$(jc_digest "$target")
   if [ "$current" = "$trial" ]; then
     [ "$(stat -c '%d:%i' "$target")" = "$(stat -c '%d:%i' "$BASE/payload/$name")" ] || { echo "Unrelated mount: $target"; exit 1; }
     umount "$target"
   elif [ "$current" != "$original" ]; then echo "Unrecognized content: $target"; exit 1; fi
   [ "$(jc_digest "$target")" = "$original" ] || exit 1
 done
}
restart_provider() {
 am force-stop com.android.camera
 am force-stop local.jc.mainraw
 setprop ctl.restart "$SERVICE"
}
rollback_error() {
 trap - EXIT HUP INT TERM
 echo 'Failure: restoring mounted files'
 restore_files && rm -f "$BASE/active"
 restart_provider
}
identity
case "$ACTION" in
status)
 echo "service=$(getprop init.svc.$SERVICE) pid=$(getprop init.svc_debug_pid.$SERVICE)"
 if [ -f "$BASE/active" ]; then cat "$BASE/active"; else echo inactive; fi
 each | while IFS='|' read -r name target original trial context; do sha256sum "$target"; done
 ;;
apply)
 [ ! -f "$BASE/active" ] || { echo 'Trial already active'; exit 1; }
 mkdir -p "$BASE/backup"
 chmod 0700 "$BASE" "$BASE/backup"
 each | while IFS='|' read -r name target original trial context; do
   [ "$(jc_digest "$target")" = "$original" ] || { echo "Original hash mismatch: $target"; exit 1; }
   [ "$(jc_digest "$BASE/payload/$name")" = "$trial" ] || { echo "Payload hash mismatch: $name"; exit 1; }
   if [ ! -f "$BASE/backup/$name" ]; then cp "$target" "$BASE/backup/$name"; fi
   [ "$(jc_digest "$BASE/backup/$name")" = "$original" ] || { echo 'Backup mismatch'; exit 1; }
   chown 0:0 "$BASE/payload/$name"
   chmod 0644 "$BASE/payload/$name"
   chcon "$context" "$BASE/payload/$name"
 done
 printf 'PHOTO_V5_APPLYING\n' > "$BASE/active"
 trap rollback_error EXIT HUP INT TERM
 am force-stop com.android.camera
 am force-stop local.jc.mainraw
 each | while IFS='|' read -r name target original trial context; do
   mount --bind "$BASE/payload/$name" "$target"
   [ "$(jc_digest "$target")" = "$trial" ]
 done
 oldpid=$(getprop init.svc_debug_pid.$SERVICE)
 setprop ctl.restart "$SERVICE"
 count=0
 while [ "$(getprop init.svc.$SERVICE)" != running ] || [ "$(getprop init.svc_debug_pid.$SERVICE)" = "$oldpid" ]; do
   count=$((count+1)); [ "$count" -lt 12 ] || exit 1; sleep 1
 done
 date +%s > "$BASE/started"
 printf 'PHOTO_V5_ACTIVE_TEMPORARY\n' > "$BASE/active"
 trap - EXIT HUP INT TERM
 nohup /system/bin/sh "$BASE/payload/control.sh" watch > "$BASE/watch.log" 2>&1 < /dev/null &
 echo $! > "$BASE/watch.pid"
 echo 'Photo V5 configurations and stock-based library port mounted. Reboot or revert restores originals.'
 ;;
revert)
 [ -f "$BASE/active" ] || { echo 'No recorded active trial'; exit 1; }
 restore_files
 rm -f "$BASE/active"
 restart_provider
 echo 'Original files restored; camera provider restarted.'
 ;;
watch)
 # Automatically roll back if provider repeatedly dies during this temporary trial.
 previous=$(getprop init.svc_debug_pid.$SERVICE)
 changes=0; failures=0
 while [ -f "$BASE/active" ]; do
   sleep 3
   [ -f "$BASE/active" ] || exit 0
   state=$(getprop init.svc.$SERVICE); current=$(getprop init.svc_debug_pid.$SERVICE)
   if [ "$state" != running ] || [ -z "$current" ]; then failures=$((failures+1)); else failures=0; fi
   if [ -n "$current" ] && [ "$current" != "$previous" ]; then changes=$((changes+1)); previous=$current; fi
   if [ "$failures" -ge 3 ] || [ "$changes" -ge 2 ]; then
     echo 'Provider instability detected: automatic restore'
     /system/bin/sh "$BASE/payload/control.sh" revert
     exit 0
   fi
 done
 ;;
*) echo 'Use status / apply / revert'; exit 2;;
esac
