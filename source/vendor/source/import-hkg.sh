#!/system/bin/sh
# Invoked by the validated, serialized public installer.
umask 077
here=${0%/*}
[ "$#" = 2 ] && [ -d "$1" ] && [ -f "$here/vendor-import.jar" ] && [ ! -L "$here/vendor-import.jar" ] || exit 2
export ANDROID_ROOT=/system ANDROID_DATA=/data CLASSPATH="$here/vendor-import.jar"
exec /system/bin/app_process /system/bin kr.ioniq5.publiccp.VendorAssets "$1" "$2"
