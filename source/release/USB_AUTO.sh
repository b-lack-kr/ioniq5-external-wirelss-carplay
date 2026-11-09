#!/system/bin/sh
# USB entry only. The signed CarPlay mod, native package and installer are immutable.
# NEMESIS has already obtained the user's USB-execute confirmation before this runs.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
umask 077
[ "$#" = 0 ] || exit 2
case "$(id)" in 'uid=0('*|'uid=0 '*|'uid=0') ;; *) exit 2;; esac
[ "$(getprop ro.build.product)" = NEEV.KOR.0000.V039.001.260902 ] || exit 2
[ ! -L "$0" ] || exit 2
USB=${0%/*}; [ "$USB" != "$0" ] || USB=$PWD
USB=$(cd "$USB" && pwd -P) || exit 2
case "$USB" in
    /storage/usb0|/storage/usb1|/storage/usb2|/mnt/media_rw/usb0|/mnt/media_rw/usb1|/mnt/media_rw/usb2) ;;
    *) exit 2;;
esac
OUT="$USB/USB_AUTO_RESULT_$(date +%Y%m%d_%H%M%S)_$$"
mkdir "$OUT" || exit 2
exec > "$OUT/RUN.log" 2>&1
W=/data/nemesis/neev-usbcp
CP=/data/hmgapi/mods/neev-usb-carplay
PUBLIC="$W/public-release-1.0.3"
CLI=/system/xbin/nemesis
PACKAGE="$USB/packages/hmgapi.neev-usb-carplay.zip"
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
directory() { [ -d "$1" ] && [ ! -L "$1" ]; }
exists() { [ -e "$1" ] || [ -L "$1" ]; }
status() { echo "$1" > "$OUT/STATUS.txt" && sync || exit 2; echo "$1"; }
notice() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || :
}
fail() { status "$1"; notice "CarPlay USB 실행 실패: $1. USB 기록을 확인하세요"; exit 1; }
hash_is() {
    regular "$1" || return 1
    digest=$(md5 "$1") && [ "${digest%% *}" = "$2" ]
}
known_version() {
    case "$1" in 0.1.10|0.1.11|1.0.0|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5|1.0.6|1.0.7|1.0.8|1.0.9|1.0.10|1.0.11|1.0.12|1.0.13|1.0.14|1.0.15) return 0;; *) return 1;; esac
}
read_one_line() {
    regular "$1" || return 1
    (
        exec 3< "$1" || exit 1
        IFS= read -r value <&3 || exit 1
        extra=
        if IFS= read -r extra <&3 || [ -n "$extra" ]; then exit 1; fi
        # The vehicle shell has no printf. Only literal state/version tokens
        # may reach echo: never let options or backslash escapes change a value.
        case "$value" in ''|-*|*[!A-Za-z0-9._-]*) exit 1;; esac
        echo "$value"
    )
}
status CHECKING
directory "$USB/packages" || fail USB_PACKAGE_DIRECTORY
hash_is "$USB/NEMESIS.sh" @AUTO_WRAPPER_MD5@ &&
    hash_is "$USB/NEEV_CP_INSTALL.sh" @AUTO_LAUNCHER_MD5@ &&
    hash_is "$PACKAGE" @AUTO_PACKAGE_MD5@ || fail USB_FILES_CHANGED
info=$("$CLI" usb-info) || fail USB_QUERY_FAILED
usb_root=; mounted=; seen_root=0; seen_mounted=0
while IFS= read -r row; do
    case "$row" in
        root=*) [ "$seen_root" = 0 ] || fail USB_QUERY_AMBIGUOUS; seen_root=1; usb_root=${row#root=};;
        mounted=*) [ "$seen_mounted" = 0 ] || fail USB_QUERY_AMBIGUOUS; seen_mounted=1; mounted=${row#mounted=};;
    esac
done <<END_USB
$info
END_USB
[ "$mounted" = 1 ] && [ "$usb_root" = "$USB" ] || fail USB_QUERY_MISMATCH

# Restore requires the separate manual collector entry, never this automatic mode.
if exists "$USB/NEEV-CP-RESTORE"; then
    fail RESTORE_REQUIRES_MANUAL_MODE
fi

info=$("$CLI" pkg-info "$PACKAGE") || fail PACKAGE_QUERY_FAILED
pkg_id=; pkg_version=; installed=; installed_version=
seen_id=0; seen_version=0; seen_installed=0; seen_installed_version=0
while IFS= read -r row; do
    case "$row" in
        id=*) [ "$seen_id" = 0 ] || fail PACKAGE_QUERY_AMBIGUOUS; seen_id=1; pkg_id=${row#id=};;
        package-version=*) [ "$seen_version" = 0 ] || fail PACKAGE_QUERY_AMBIGUOUS; seen_version=1; pkg_version=${row#package-version=};;
        installed=*) [ "$seen_installed" = 0 ] || fail PACKAGE_QUERY_AMBIGUOUS; seen_installed=1; installed=${row#installed=};;
        installed-version=*) [ "$seen_installed_version" = 0 ] || fail PACKAGE_QUERY_AMBIGUOUS; seen_installed_version=1; installed_version=${row#installed-version=};;
    esac
done <<END_PACKAGE
$info
END_PACKAGE
[ "$pkg_id" = hmgapi.neev-usb-carplay ] && [ "$pkg_version" = 1.0.3 ] &&
    [ "$seen_installed" = 1 ] && [ "$seen_installed_version" = 1 ] || fail PACKAGE_QUERY_INVALID
case "$installed" in 0|1) ;; *) fail PACKAGE_QUERY_INVALID;; esac

workspace_version=
if exists "$W"; then
    directory "$W" || fail WORKSPACE_INVALID
    workspace_version=$(read_one_line "$W/UPGRADE_CURRENT.txt") || fail WORKSPACE_VERSION_INVALID
    known_version "$workspace_version" || fail UNSUPPORTED_WORKSPACE_VERSION
fi
if exists "$CP"; then directory "$CP" || fail MOD_DIRECTORY_INVALID; fi

if [ "$installed" = 1 ]; then
    known_version "$installed_version" || fail UNSUPPORTED_INSTALLED_VERSION
    directory "$CP" || fail INSTALL_STATE_MISMATCH
    [ "$workspace_version" = "$installed_version" ] || fail INSTALL_STATE_MISMATCH
    if [ "$installed_version" = 1.0.3 ]; then
        exists "$PUBLIC" || fail UNINSTALL_INTERNAL_TEST_VERSION
        directory "$PUBLIC" && [ "$(read_one_line "$PUBLIC/STATUS")" = INSTALLED ] || fail SAME_VERSION_INCOMPLETE
        # The original collector performs its full signed-file checks. A failure is
        # propagated; no retry, remove, install, force or downgrade is attempted.
        status COLLECTING
        /system/bin/sh "$USB/NEMESIS.sh" --collect
        rc=$?
        [ "$rc" = 0 ] || { status COLLECT_FAILED; exit "$rc"; }
        status COLLECT_COMPLETE
        exit 0
    fi
    if [ "$installed_version" = 1.0.2 ] && exists "$W/public-release-1.0.2"; then
        status UNINSTALL_PREVIOUS_RELEASE
        notice 'CarPlay 1.0.2를 NEMESIS에서 삭제한 뒤 1.0.3을 설치하세요'
        exit 1
    fi
    [ "$installed_version" = 1.0.1 ] || fail UNINSTALL_INTERNAL_TEST_VERSION
    if exists "$PUBLIC"; then
        # A completed explicit rollback may be retried. Never ask the old
        # installer to recover an in-progress transaction automatically.
        directory "$PUBLIC" && [ "$(read_one_line "$PUBLIC/STATUS")" = RESTORED ] || fail INCOMPLETE_TRANSACTION
    fi
    status UPDATING
    "$CLI" install --update "$PACKAGE"
else
    [ -z "$installed_version" ] && ! exists "$CP" || fail INSTALL_STATE_MISMATCH
    # The immutable installer verifies any retained uninstall history itself.
    # Existing incomplete transactions must be resolved explicitly.
    if exists "$PUBLIC"; then
        directory "$PUBLIC" && [ "$(read_one_line "$PUBLIC/STATUS")" = INSTALLED ] || fail INCOMPLETE_TRANSACTION
    fi
    status INSTALLING
    "$CLI" install "$PACKAGE"
fi
rc=$?
[ "$rc" = 0 ] || { status INSTALL_FAILED; exit "$rc"; }
directory "$CP" && directory "$W" && directory "$PUBLIC" &&
    [ "$(read_one_line "$W/UPGRADE_CURRENT.txt")" = 1.0.3 ] &&
    [ "$(read_one_line "$PUBLIC/STATUS")" = INSTALLED ] || fail INSTALL_RESULT_UNVERIFIED
status INSTALL_COMPLETE
