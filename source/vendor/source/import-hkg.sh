#!/system/bin/sh
# Invoked by the validated, serialized public installer.
umask 077
here=${0%/*}
[ "$#" = 2 ] && [ -d "$1" ] && [ -f "$here/vendor-import.jar" ] && [ ! -L "$here/vendor-import.jar" ] || exit 2
@CLI_RUNNER@
cli_java "$here/vendor-import.jar" kr.ioniq5.publiccp.VendorAssets "$1" "$2"
