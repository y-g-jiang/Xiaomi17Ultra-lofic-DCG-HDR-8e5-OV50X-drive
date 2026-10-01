#!/system/bin/sh
set -eu
B=/data/adb/jc-lofic-unity
T=/odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so
C=/odm/etc/camera/camxoverridesettings.txt
P=/data/adb/jc-lofic-photo
STOCK=145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b
UNITY=3af9d5797de98db030d1b737983a60100a02a43c269d5e6d5850c324d8341233
case "${1:-status}" in
stage|stock|unity|cleanup)
 [ "$(id -u)" = 0 ]; [ "$(getprop ro.product.device)" = nezha ]
 [ "$(getprop ro.build.version.incremental)" = OS3.0.312.0.WPACNXM ]
 [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ]
 if [ "${JC_UNITY_LOCKED:-0}" != 1 ]; then
  [ -d "$B" ]; exec 9>"$B/controller.lock"
  n=0
  while ! flock -n 9; do n=$((n+1)); [ "$n" -lt 600 ]; sleep .1; done
  JC_UNITY_LOCKED=1; export JC_UNITY_LOCKED
 fi
 ;;
esac

digest() { sha256sum "$1" | cut -d ' ' -f 1; }
unmount_gain() {
 current=$(digest "$T")
 case "$current" in
  "$STOCK") return 0;;
  "$UNITY") source=$B/unity.so;;
  *) echo Unknown_sensor_library; return 1;;
 esac
 [ "$(stat -c '%d:%i' "$T")" = "$(stat -c '%d:%i' "$source")" ]; umount -l "$T"
 [ "$(digest "$T")" = "$STOCK" ]
}
case "${1:-status}" in
stage)
 [ -d "$B" ]; [ "$(dirname "$(readlink -f "$0")")" = "$B" ]
 [ "$(id -u)" = 0 ]; [ "$(getprop ro.product.device)" = nezha ]
 [ "$(getprop ro.build.version.incremental)" = OS3.0.312.0.WPACNXM ]
 [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ]
 [ ! -f "$B/armed" ]; [ ! -f "$P/active" ]; [ ! -f /data/adb/jc-app-native/active ]
 [ "$(digest "$T")" = "$STOCK" ]; [ "$(digest "$B/unity.so")" = "$UNITY" ]
 [ "$(digest "$C")" = 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b ]
 cp "$C" "$B/stock_camx.txt"
 [ "$(digest "$B/stock_camx.txt")" = 27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b ]
 digest "$C" > "$B/initial_camx.sha"; cp "$C" "$B/initial_camx.txt"
 chmod 0644 "$B/unity.so" "$B/stock_camx.txt"
 chcon u:object_r:vendor_file:s0 "$B/unity.so"
 chcon u:object_r:vendor_configs_file:s0 "$B/stock_camx.txt"
 touch "$B/armed"; date +%s > "$B/heartbeat"
 trap 'sh "$B/control.sh" cleanup' EXIT
 trap 'exit 124' HUP INT TERM
 mount --bind "$B/stock_camx.txt" "$C"
 sh "$P/payload/control.sh" apply 9>&-
 JC_UNITY_LOCKED=0 nohup sh "$B/control.sh" watch > "$B/watch.log" 2>&1 < /dev/null 9>&- &
 echo $! > "$B/watch.pid"
 trap - EXIT HUP INT TERM
 echo STAGED
 ;;
stock|unity)
 [ -f "$B/armed" ]; [ ! -f /data/adb/jc-app-native/active ]
 date +%s > "$B/heartbeat"
 unmount_gain
 if [ "$1" != stock ]; then
  expected=$UNITY
  [ "$(digest "$B/$1.so")" = "$expected" ]
  mount --bind "$B/$1.so" "$T"
 fi
 digest "$T"
 ;;
touch) [ -f "$B/armed" ]; date +%s > "$B/heartbeat";;
cleanup)
 [ -f "$B/armed" ] || exit 0
 if [ -f /data/adb/jc-app-native/active ]; then
  session=$(cat /data/adb/jc-app-native/active)
  case "$session" in s*[!0-9]*|'') exit 1;;s*) sh "/data/adb/jc-app-native/$session/control.sh" restore "$session" 9>&-;;*) exit 1;;esac
 fi
 unmount_gain
 if [ -f "$P/active" ]; then sh "$P/payload/control.sh" revert 9>&-; fi
 if [ "$(stat -c '%d:%i' "$C")" = "$(stat -c '%d:%i' "$B/stock_camx.txt")" ]; then umount "$C"; fi
 [ "$(digest "$C")" = "$(cat "$B/initial_camx.sha")" ]
 rm -f "$B/armed"
 setprop ctl.restart vendor.camera-provider
 echo RESTORED_INITIAL_STATE
 ;;
watch)
 while [ -f "$B/armed" ]; do
  sleep 10
  if [ $(( $(date +%s)-$(cat "$B/heartbeat") )) -gt 600 ]; then sh "$B/control.sh" cleanup; exit; fi
 done;;
status)
 digest "$T"; digest "$C"; getprop init.svc.vendor.camera-provider
 [ ! -f "$B/armed" ] || echo ARMED
 ;;
*) exit 1;;
esac
