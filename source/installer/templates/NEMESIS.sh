#!/system/bin/sh
# Bootstrap the local transaction from an authenticated USB tree. No personal data is exported.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
unset LD_PRELOAD HMGAPI_SELFTEST
umask 077
USB=${0%/*}; [ "$USB" != "$0" ] || USB=$PWD
USB=$(cd "$USB" && pwd -P) || exit 2
W=/data/nemesis/neev-usbcp
R=$W/public-1.0.0
H=/data/hmgapi
ID=neev-usb-carplay
MOD=$H/mods/$ID
PACK=$USB/NEEV_CP
MANIFEST=@PACK_MD5@
[ "$(getprop ro.build.product)" = NEEV.KOR.0000.V039.001.260902 ] && [ "$(getprop ro.board.platform)" = tcc803x ] || exit 2
[ "$(cat /proc/sys/kernel/osrelease)" = 3.18.24-tcc ] && [ "$(getprop sys.boot_completed)" = 1 ] || exit 2
case "$(id)" in 'uid=0('*|'uid=0 '*|'uid=0') ;; *) exit 2;; esac
OUT="$USB/NEEV_CP_RESULT_$(date +%Y%m%d_%H%M%S)_$$"
mkdir "$OUT" || exit 2
exec > "$OUT/install.log" 2>&1
notice() { echo "$1"; am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || :; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
absent() { [ ! -e "$1" ] && [ ! -L "$1" ]; }
dir() { [ -d "$1" ] && [ ! -L "$1" ]; }
hash() { set -- $(md5 "$1" 2>/dev/null); echo "$1"; }
stop() { echo "$1" > "$OUT/STATUS.txt"; sync; notice "CarPlay 설치 중단: $1"; exit 4; }
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 2
case "$boot" in ''|*[!a-f0-9-]*) exit 2;; esac
check_kit() {
    dir "$1" && regular "$1/MANIFEST.md5" && [ "$(hash "$1/MANIFEST.md5")" = "${2:-$MANIFEST}" ] || return 1
    while read digest path; do
        case "$path" in ''|/*|*..*|.*|*/.*) return 1;; esac
        regular "$1/$path" && [ "$(hash "$1/$path")" = "$digest" ] || return 1
    done < "$1/MANIFEST.md5"
}
# Reruns and recovery must use the verified local transaction, including
# the original 1.0.0 whose signer is unchanged and whose metadata predates branding.
check_recorded_kit() {
    if regular "$R/kit/MANIFEST.md5" && [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        [ "$(hash "$R/kit/MANIFEST.md5")" = 38eac52adfc6d6d719e64c1da2957dbf ]; then
        check_kit "$R/kit" 38eac52adfc6d6d719e64c1da2957dbf
    else check_kit "$R/kit"; fi
}
check_kit "$PACK" || stop INVALID_USB_PACKAGE
[ "$(/system/bin/hmgapid version)" = 'hmgapid 1.0.1' ] && /system/bin/hmgapid ping >/dev/null || stop HMG_API_101_REQUIRED
dir /data/nemesis && dir "$H" && dir "$H/maps" && dir "$H/mods" && dir "$H/hook" && dir "$H/state" || stop HMG_API_NOT_READY
[ ! -L "$W" ] && [ ! -L "$R" ] && [ ! -L "$MOD" ] || stop UNSAFE_PATH
restore=0
if ! absent "$USB/NEEV-CP-RESTORE"; then
    regular "$USB/NEEV-CP-RESTORE" && [ ! -s "$USB/NEEV-CP-RESTORE" ] || stop INVALID_RESTORE_MARKER
    restore=1
fi
# A dead worker from an earlier boot cannot race this transaction. In the same
# boot we refuse an abandoned lock rather than racing an orphaned engine child.
LOCK="/data/nemesis/.neev-cp-public-$boot.lock"
mkdir "$LOCK" || stop BUSY_OR_REBOOT_REQUIRED
legacy_lock=0
cleanup() {
    rc=$?
    trap - EXIT
    if [ "$legacy_lock" = 1 ] && [ "$(cat "$W/v017.lock/owner" 2>/dev/null)" = "$boot $$" ]; then rm -f "$W/v017.lock/owner" && rmdir "$W/v017.lock"; fi
    if regular "$R/STATUS"; then cat "$R/STATUS" > "$OUT/CP_STATUS.txt"; fi
    if regular "$R/mode"; then cat "$R/mode" > "$OUT/INSTALL_MODE.txt"; fi
    if [ "$rc" = 0 ]; then echo COMPLETE > "$OUT/STATUS.txt"; elif [ ! -s "$OUT/STATUS.txt" ]; then echo "FAILED rc=$rc" > "$OUT/STATUS.txt"; fi
    rm -f "$LOCK/after-remove-plan.txt"; rmdir "$LOCK"; sync; exit "$rc"
}
trap cleanup EXIT
trap 'exit 6' HUP INT TERM
if dir "$W" && ! absent "$W/v017.lock"; then
    dir "$W/v017.lock" && regular "$W/v017.lock/owner" || stop LEGACY_LOCK_UNKNOWN
    read -r oldboot oldpid rest < "$W/v017.lock/owner"
    case "$oldboot" in ''|*[!a-f0-9-]*) stop LEGACY_LOCK_UNKNOWN;; esac
    [ "$oldboot" != "$boot" ] || stop LEGACY_UPDATER_BUSY
    mv "$W/v017.lock" "$W/public-old-lock-$boot-$$" || stop BUSY
fi
# Engine.remove and NEMESIS uninstall remove the mod, but do not own the old
# USB workspace or its preferences. Retire only a verified completed install.
verify_removed_source() {
    dir "$1" && dir "$1/META-HMGAPI" && regular "$1/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/META-HMGAPI/manifest.json")" = "$2" ] || return 1
    ( export ANDROID_ROOT=/system ANDROID_DATA=/data CLASSPATH=/system/framework/hmgapi-hook.jar
      exec /system/bin/app_process /system/bin hmgapi.engine.Signing verify "$1" "@OWNER@" )
}
verify_removed_workspace() {
    regular "$W/UPGRADE_CURRENT.txt" || return 1
    version=$(cat "$W/UPGRADE_CURRENT.txt")
    if dir "$R"; then
        [ "$version" = 1.0.0 ] && regular "$R/STATUS" && [ "$(cat "$R/STATUS")" = INSTALLED ] &&
            regular "$R/mode" && dir "$R/kit/payload" && check_recorded_kit || return 1
        case "$(cat "$R/mode")" in 'fresh'|'0.1.10'|'0.1.11') ;; *) return 1;; esac
        verify_removed_source "$R/kit/payload/mod" "$(hash "$R/kit/payload/mod/META-HMGAPI/manifest.json")"
        return
    fi
    absent "$R" && regular "$W/BACKUP_COMPLETE" && regular "$W/MANIFEST.md5" &&
        [ "$(hash "$W/MANIFEST.md5")" = 468a2a355ca642dc0b2cfb4c3a45204f ] &&
        regular "$W/STATUS.txt" && [ "$(cat "$W/STATUS.txt")" = INSTALLED_REBOOT_REQUIRED ] || return 1
    case "$version" in
        0.1.10) rev=0110; expected=b82a23b7e928c80d4c90fa16e8767eb8;;
        0.1.11) rev=0111; expected=c57459b715e3e5eeb6e6fc5c8bdd35e1;;
        *) return 1;;
    esac
    regular "$W/V${rev}_ACTIVE.txt" && regular "$W/V${rev}_STATUS.txt" &&
        [ "$(cat "$W/V${rev}_STATUS.txt")" = UPGRADED_REBOOT_REQUIRED ] || return 1
    last=$(cat "$W/V${rev}_ACTIVE.txt")
    case "$last" in *[!a-z0-9-]*) return 1;; v"$rev"-*) ;; *) return 1;; esac
    prev="$W/upgrades/$last"
    dir "$W/upgrades" && dir "$prev" && dir "$prev/payload" && regular "$prev/STATUS.txt" &&
        [ "$(cat "$prev/STATUS.txt")" = UPGRADED_REBOOT_REQUIRED ] &&
        verify_removed_source "$prev/payload/mod" "$expected"
}
retire_removed_workspace() {
    absent "$H/state/journal.json" || stop UNFINISHED_HMG_CHANGE
    verify_removed_workspace || stop UNOWNED_REMOVED_SETUP
    if ! dir "$USB/packages" || ! regular "$USB/packages/usbwifi.hotspot.zip"; then
        regular "$USB/HKG_5W_USBWifi_Hotspot.zip" || stop E_VENDOR_ASSETS
    fi
    # Apply changes in the management app finishes removal using the owner's
    # selected plan. Never apply unrelated pending changes here.
    ( export ANDROID_ROOT=/system ANDROID_DATA=/data CLASSPATH=/system/framework/hmgapi-hook.jar
      exec /system/bin/app_process /system/bin hmgapi.engine.Engine plan ) > "$LOCK/after-remove-plan.txt" 2>&1 || stop APPLY_CHANGES_AFTER_REMOVE
    while read -r kind rest; do
        case "$kind" in 'change'|'update'|'restore'|'setting'|'bind'|'unbind'|'problem') stop APPLY_CHANGES_AFTER_REMOVE;; esac
    done < "$LOCK/after-remove-plan.txt"
    dir "$H/settings" || absent "$H/settings" || stop UNSAFE_MOD_SETTINGS
    prefs="$H/settings/$ID.json"
    saved="$W/.manual-uninstall-settings.json"
    absent "$prefs" || { regular "$prefs" && absent "$saved"; } || stop UNSAFE_MOD_SETTINGS
    absent "$saved" || regular "$saved" || stop UNSAFE_MOD_SETTINGS
    # Keep the legacy installer out until the active workspace is atomically
    # moved. Moving prefs first leaves a verifiable workspace after a power cut.
    mkdir "$W/v017.lock" || stop LEGACY_UPDATER_BUSY
    legacy_lock=1
    echo "$boot $$" > "$W/v017.lock/owner" || stop LOCK_WRITE
    absent "$MOD" || stop REMOVED_MOD_REAPPEARED
    archive="/data/nemesis/neev-usbcp-removed-$boot-$$"
    absent "$archive" || stop REMOVED_ARCHIVE_EXISTS
    if regular "$prefs"; then mv "$prefs" "$saved" && sync || stop RETIRE_MOD_SETTINGS; fi
    mv "$W" "$archive" && sync || stop RETIRE_REMOVED_SETUP
    # With the old vendor cache parked, the existing importer can only verify
    # the provided USB source. Return the workspace if the input is damaged.
    if ! /system/bin/sh "$PACK/import-hkg.sh" "$USB" check; then
        absent "$W" && absent "$MOD" && mv "$archive" "$W" || stop RETIRED_SETUP_REQUIRES_ATTENTION
        if regular "$saved"; then absent "$prefs" && mv "$saved" "$prefs" || stop RESTORE_MOD_SETTINGS; fi
        stop E_VENDOR_ASSETS
    fi
    legacy_lock=0
    echo "RETIRED_MANUAL_UNINSTALL=$version"
}
if [ "$restore" = 0 ] && dir "$W" && absent "$MOD"; then
    if absent "$R" || { dir "$R" && regular "$R/STATUS" && [ "$(cat "$R/STATUS")" = INSTALLED ]; }; then
        retire_removed_workspace
    fi
fi
if ! dir "$R"; then
    [ "$restore" = 0 ] || stop NO_PUBLIC_INSTALL
    if absent "$W"; then
        absent "$MOD" || stop UNOWNED_INSTALLED_MOD
        mode=fresh
    else
        dir "$W" && dir "$MOD" && regular "$W/MANIFEST.md5" && [ "$(hash "$W/MANIFEST.md5")" = 468a2a355ca642dc0b2cfb4c3a45204f ] && regular "$W/BACKUP_COMPLETE" || stop UNOWNED_SETUP
        regular "$W/UPGRADE_CURRENT.txt" && regular "$MOD/META-HMGAPI/manifest.json" || stop UNOWNED_SETUP
        [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] || stop LEGACY_SIGNER_MISMATCH
        mode=$(cat "$W/UPGRADE_CURRENT.txt")
        case "$mode:$(hash "$MOD/META-HMGAPI/manifest.json")" in
            0.1.10:b82a23b7e928c80d4c90fa16e8767eb8|0.1.11:c57459b715e3e5eeb6e6fc5c8bdd35e1) ;;
            *) stop UNSUPPORTED_EXISTING_VERSION;;
        esac
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
    fi
    /system/bin/sh "$PACK/import-hkg.sh" "$USB" check || stop E_VENDOR_ASSETS
    # Build and validate the entire transaction off to the side before publishing it.
    STAGE="/data/nemesis/.neev-public-stage-$boot-$$"
    absent "$STAGE" && mkdir "$STAGE" && mkdir "$STAGE/kit" || stop STAGING
    while read digest path; do
        case "$path" in */*) mkdir -p "$STAGE/kit/${path%/*}" || stop STAGING;; esac
        cat "$PACK/$path" > "$STAGE/kit/$path" || stop STAGING
    done < "$PACK/MANIFEST.md5"
    cat "$PACK/MANIFEST.md5" > "$STAGE/kit/MANIFEST.md5" && check_kit "$STAGE/kit" || stop STAGING
    echo "$mode" > "$STAGE/mode" && echo PREPARED > "$STAGE/STATUS" && sync || stop STAGING
    if [ "$mode" = fresh ]; then
        FRESH="/data/nemesis/.neev-fresh-$boot-$$"
        absent "$FRESH" && mkdir "$FRESH" && mv "$STAGE" "$FRESH/public-1.0.0" && absent "$W" && mv "$FRESH" "$W" || stop STAGING
    else mv "$STAGE" "$R" || stop STAGING; fi
fi
check_recorded_kit && regular "$R/mode" && regular "$R/STATUS" || stop LOCAL_TRANSACTION_DAMAGED
dir "$W" && mkdir "$W/v017.lock" || stop LEGACY_UPDATER_BUSY
legacy_lock=1
echo "$boot $$" > "$W/v017.lock/owner" || stop LOCK_WRITE
U=$R/kit
. "$U/operate.sh"
