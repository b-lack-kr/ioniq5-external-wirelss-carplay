# Native NEMESIS hooks run with this package root as cwd. The CarPlay USB
# transaction remains byte-for-byte unchanged and owns all installation checks.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
umask 077
fail() { echo "CarPlay USB package: $*" >&2; exit 1; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
hash_is() {
    regular "$1" || return 1
    digest=$(md5 "$1") || return 1
    [ "${digest%% *}" = "$2" ]
}
carplay_usb() {
    info=$(/system/xbin/nemesis usb-info) || fail USB_NOT_READY
    USB=
    mounted=
    while IFS= read -r row; do
        case "$row" in
            root=*) [ -z "$USB" ] || fail AMBIGUOUS_USB; USB=${row#root=};;
            mounted=*) [ -z "$mounted" ] || fail AMBIGUOUS_USB; mounted=${row#mounted=};;
        esac
    done <<END_USB_INFO
$info
END_USB_INFO
    [ "$mounted" = 1 ] || fail USB_NOT_READY
    case "$USB" in
        /storage/usb0|/storage/usb1|/storage/usb2|/mnt/media_rw/usb0|/mnt/media_rw/usb1|/mnt/media_rw/usb2) ;;
        *) fail INVALID_USB_ROOT;;
    esac
    [ -d "$USB" ] || fail USB_NOT_READY
    hash_is "$USB/NEMESIS.sh" @CP_WRAPPER_MD5@ &&
        hash_is "$USB/NEEV_CP_INSTALL.sh" @CP_LAUNCHER_MD5@ || fail CARPLAY_USB_FILES_MISMATCH
}
