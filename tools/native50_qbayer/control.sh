#!/system/bin/sh
set -eu
B=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
C=/odm/etc/camera/camxoverridesettings.txt
M=/odm/lib64/camera/com.qti.sensormodule.nezha_semco_ovx10500u_wide_i.bin
S=/odm/lib64/camera/com.qti.sensor.nezha_semco_ovx10500u_wide_i.so
CS=27d120f36ab423df98eda6d5e4582aa639e1f627a0adcedaf56de8ff877abe2b
MS=dd38771f4aa864fda6b80933da794c2097eef8ee12fc748f5189fd98a656e613
MP=32a715889eb54d601ade4a58cd76ff640b9c89631a2ab6f33fc7c1495cbb200d
SS=145b0201b3e5de39ba7ccf7bb7219d96d8ad2a1e3925f9ced8aa41857cec989b
digest() { sha256sum "$1" | cut -d ' ' -f 1; }
same_inode() { [ "$(stat -c '%d:%i' "$1")" = "$(stat -c '%d:%i' "$2")" ]; }
provider() {
 setprop "ctl.$1" vendor.camera-provider
 n=0
 while [ "$(getprop init.svc.vendor.camera-provider)" != "$2" ]; do
  n=$((n+1)); [ "$n" -lt 100 ] || return 1; sleep .1
 done
}
[ "$(id -u)" = 0 ]
[ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ]
exec 9>"$B/lock"
if [ "${JC_QB_LOCKED:-0}" != 1 ]; then toybox flock -x 9; fi
case "${1:-status}" in
apply)
 [ "$(getprop ro.product.device)" = nezha ]
 [ "$(getprop ro.build.version.incremental)" = OS3.0.312.0.WPACNXM ]
 [ ! -f "$B/active" ]
 [ ! -f /data/adb/jc-lofic-photo/active ]
 [ ! -f /data/adb/jc-app-native/active ]
 [ ! -d /data/adb/jc-native50/lock ]
 [ ! -d /data/adb/jc-pair-camera/lock ]
 [ "$(digest "$C")" = "$CS" ]
 [ "$(digest "$M")" = "$MS" ]
 [ "$(digest "$S")" = "$SS" ]
 [ "$(digest "$B/sensormodule.bin")" = "$MP" ]
 [ -s "$B/camx.txt" ]
 digest "$B/camx.txt" > "$B/camx.sha"
 chmod 0644 "$B/camx.txt" "$B/sensormodule.bin"
 chcon u:object_r:vendor_configs_file:s0 "$B/camx.txt"
 chcon u:object_r:vendor_file:s0 "$B/sensormodule.bin"
 am force-stop com.android.camera
 am force-stop local.jc.mainraw
 touch "$B/active"
 trap 'JC_QB_LOCKED=1 sh "$B/control.sh" restore' EXIT
 trap 'exit 124' HUP INT TERM
 provider stop stopped
 mount --bind "$B/camx.txt" "$C"
 mount --bind "$B/sensormodule.bin" "$M"
 [ "$(digest "$C")" = "$(cat "$B/camx.sha")" ]
 [ "$(digest "$M")" = "$MP" ]
 provider start running
 date +%s > "$B/started"
 JC_QB_LOCKED=0 nohup sh "$B/control.sh" watch > "$B/watch.log" 2>&1 < /dev/null 9>&- &
 echo $! > "$B/watch.pid"
 trap - EXIT HUP INT TERM
 echo ACTIVE
 ;;
restore)
 [ -f "$B/active" ] || exit 0
 am force-stop com.android.camera
 am force-stop local.jc.mainraw
 provider stop stopped
 trap 'setprop ctl.start vendor.camera-provider' EXIT
 if same_inode "$M" "$B/sensormodule.bin"; then umount "$M"; fi
 if same_inode "$C" "$B/camx.txt"; then umount "$C"; fi
 [ "$(digest "$M")" = "$MS" ]
 [ "$(digest "$C")" = "$CS" ]
 [ "$(digest "$S")" = "$SS" ]
 provider start running
 rm -f "$B/active"
 trap - EXIT
 echo RESTORED
 ;;
watch)
 exec 9>&-
 n=0
 while [ -f "$B/active" ] && [ "$n" -lt 240 ]; do
  count=$(find /data/vendor/camera -maxdepth 2 -type f -newer "$B/started" -size +40M | wc -l)
  [ "$count" -lt 3 ] || break
  free=$(df -k /data | tail -n 1 | awk '{print $4}')
  [ "$free" -ge 20000000 ] || break
  sleep 1
  n=$((n+1))
 done
 sh "$B/control.sh" restore
 ;;
files)
 find /data/vendor/camera -maxdepth 1 -type f -newer "$B/started" -name '*port[[]55[]]*'
 ;;
status)
 digest "$C"; digest "$M"; digest "$S"
 getprop init.svc.vendor.camera-provider
 [ ! -f "$B/active" ] || echo ACTIVE
 ;;
*) exit 2;;
esac
