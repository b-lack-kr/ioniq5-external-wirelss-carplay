#!/system/bin/sh
[ "$HMGAPI_NEMESIS_SYNC" != 1 ] || exit 0
. ./scripts/common.sh
carplay_usb
