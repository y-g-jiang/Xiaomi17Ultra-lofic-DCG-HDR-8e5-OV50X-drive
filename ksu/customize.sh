#!/system/bin/sh
SKIPMOUNT=true
[ "$(getprop ro.product.device)" = nezha ] || abort nezha_required
[ "$(getprop ro.build.version.incremental)" = OS3.0.312.0.WPACNXM ] || abort unsupported_rom
cd "$MODPATH/runtime" || abort runtime_missing
sha256sum -c runtime.sha256 || abort checksum_failed
nsenter -t 1 -m /system/bin/sh "$MODPATH/runtime/tools/install/install.sh" --payload-only || abort install_failed
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
