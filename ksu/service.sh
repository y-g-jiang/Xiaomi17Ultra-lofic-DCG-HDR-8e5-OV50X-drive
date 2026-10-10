#!/system/bin/sh
set -eu
MODDIR=${0%/*}
n=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
    n=$((n+1)); [ "$n" -lt 180 ] || exit 1; sleep 2
done
cd "$MODDIR/runtime"
sha256sum -c runtime.sha256 > "$MODDIR/install.log" 2>&1
expected=$(sha256sum dist/JC_Camera.apk | cut -d ' ' -f 1)
installed=$(pm path local.jc.mainraw | sed -n 's/^package://p' | head -n 1)
if [ -n "$installed" ] && [ -f "$installed" ]; then
    actual=$(sha256sum "$installed" | cut -d ' ' -f 1)
    [ "$actual" != "$expected" ] || exit 0
fi
result=$(pm install -r "$MODDIR/runtime/dist/JC_Camera.apk" 2>&1 | cat)
printf '%s\n' "$result" >> "$MODDIR/install.log"
printf '%s\n' "$result" | grep -q '^Success$'
