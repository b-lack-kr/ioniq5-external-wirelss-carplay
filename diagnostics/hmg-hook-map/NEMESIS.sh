#!/system/bin/sh
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
umask 077
USB=$(cd "${0%/*}" && pwd -P) || exit 1
case "$USB/" in /storage/*|/mnt/*) ;; *) exit 1;; esac
OUT="$USB/HMG_MAP_DIAG_$(date +%Y%m%d_%H%M%S)_$$"
mkdir "$OUT" || exit 1
{ getprop ro.build.product; getprop ro.board.platform; getprop sys.boot_completed; } > "$OUT/vehicle.txt" 2>&1
nemesis pkg-list > "$OUT/packages.txt" 2>&1
hmgapid mods > "$OUT/mods.txt" 2>&1
for mod in neev-usb-carplay neev-carplay-heading-diagnostics; do
 p="/data/hmgapi/mods/$mod"
 [ ! -L "$p" ] || continue
 [ ! -f "$p/mod.json" ] || cp "$p/mod.json" "$OUT/$mod.json"
 if [ -e "$p/.disabled" ]; then echo disabled > "$OUT/$mod-state.txt"; else echo enabled > "$OUT/$mod-state.txt"; fi
done
for d in /data/hmgapi/maps/*/*/*/*; do
 [ -d "$d" ] && [ ! -L "$d" ] || continue
 [ -f "$d/car.json" ] && [ -f "$d/dipo.json" ] || continue
 n=$(echo "$d" | sed 's|/data/hmgapi/maps/||;s|/|_|g')
 cp "$d/car.json" "$OUT/map-$n-car.json"
 cp "$d/dipo.json" "$OUT/map-$n-dipo.json"
done
[ ! -f /data/hmgapi/state/map-check.json ] || cp /data/hmgapi/state/map-check.json "$OUT/map-check.json"
[ ! -f /data/hmgapi/state/services.status ] || cp /data/hmgapi/state/services.status "$OUT/services.status"
logcat -d -v brief 2>/dev/null | grep -E 'mods: (neev-usb-carplay|neev-carplay-heading-diagnostics)|hook point dipo\.' > "$OUT/hook-errors.log"
echo DONE > "$OUT/STATUS.txt"
sync
