#!/system/bin/sh
# Public 1.0.0: session-local installation notices and independent mute package registration.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
umask 077
USB=${0%/*}; [ "$USB" != "$0" ] || USB=$PWD
USB=$(cd "$USB" && pwd -P) || exit 2
W=/data/nemesis/neev-usbcp
H=/data/hmgapi/mods
CLI=/system/xbin/nemesis
ADDON=hmgapi.neev-carplay-mute-pause
APP=/data/nemesis/apps/$ADDON
MOD=$H/neev-carplay-mute-pause
CP=$H/neev-usb-carplay
ZIP="$USB/packages/$ADDON.zip"
RESTORE="$USB/NEEV-CP-RESTORE"
PUBLIC="$W/public-1.0.0"
UUID=@OWNER_UUID@
[ "$(getprop ro.build.product)" = NEEV.KOR.0000.V039.001.260902 ] || exit 2
case "$(id)" in 'uid=0('*|'uid=0 '*|'uid=0') ;; *) exit 2;; esac
OUT="$USB/NEEV_BUNDLE_RESULT_$(date +%Y%m%d_%H%M%S)_$$"
mkdir "$OUT" || exit 2
exec > "$OUT/INSTALL.log" 2>&1
notice() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || :
}
status() {
    echo "$1" > "$OUT/STATUS.txt" && sync || exit 2
    echo "$1"
}
fail() {
    status "$1"
    notice "$2"
    exit 1
}
hash_is() {
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    checksum=$(md5 "$1") && [ "${checksum%% *}" = "$2" ]
}
owned_mod() {
    [ -d "$1" ] && [ ! -L "$1" ] && [ ! -L "$1/META-HMGAPI" ] &&
        hash_is "$1/META-HMGAPI/manifest.json" "$2" || return 1
    ( export ANDROID_ROOT=/system ANDROID_DATA=/data CLASSPATH=/system/framework/hmgapi-hook.jar
      unset HMGAPI_SELFTEST
      exec /system/bin/app_process /system/bin hmgapi.engine.Signing verify "$1" "$UUID" )
}
has_path() { [ -e "$1" ] || [ -L "$1" ]; }
owned_mute() {
    old=90bfe998c4bcd2ec1dc173b31de5b73d
    if [ "$UUID" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] && hash_is "$MOD/META-HMGAPI/manifest.json" "$old"; then
        owned_mod "$MOD" "$old"
    else owned_mod "$MOD" @MUTE_MOD_MANIFEST@; fi
}
owned_app() {
    [ -d "$APP" ] && [ ! -L "$APP" ] && [ ! -L "$APP/scripts" ] &&
        [ ! -L "$APP/mod" ] && [ ! -L "$APP/bin" ] || return 1
    # Verify a pre-branding wrapper as a whole, so mixed or foreign scripts fail.
    if [ "$UUID" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] && hash_is "$APP/NEMESIS.data" 0f1c04b92a38aacbed5e7237865b35d3; then
        hash_is "$APP/bin/open.sh" 0f16cf46dd9e46d52a13b348d0d9c3eb || return 1
        hash_is "$APP/mod/id" a78170bf7ba78f620475e771dd157eaa || return 1
        hash_is "$APP/mod/package.zip" 24bbe063af1f776d08962db9efe3954e || return 1
        hash_is "$APP/scripts/common.sh" 09787dcfe2fdf3e106a96861683e1fec || return 1
        hash_is "$APP/scripts/postinstall.sh" 6782a8099a01aecbc948fb61d6a82edd || return 1
        hash_is "$APP/scripts/preinstall.sh" 1ef37bce6cf16b5dd0f9206c7c2edd78 || return 1
        hash_is "$APP/scripts/preuninstall.sh" 87336543d66135b2628c2fcbd20546e3 || return 1
        return 0
    fi
@VERIFY_NEMESIS_APP@
}
read_registration() {
    entries=$("$CLI" pkg-list) || return 1
    registered=0
    while IFS='|' read -r id remainder; do
        [ "$id" != "$ADDON" ] || registered=1
    done <<END_REGISTRY
$entries
END_REGISTRY
}
cp_state() { cat "$PUBLIC/STATUS" 2>/dev/null; }
cp_ready() {
    cp_kit="$USB/NEEV_CP"
    cp_pin=@CP_PAYLOAD_MANIFEST@
    mod_pin=@CP_MOD_MANIFEST@
    setup_pin=@SETUP_JAR@
    if [ "$UUID" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] && hash_is "$PUBLIC/kit/MANIFEST.md5" 38eac52adfc6d6d719e64c1da2957dbf; then
        cp_kit="$PUBLIC/kit"
        cp_pin=38eac52adfc6d6d719e64c1da2957dbf
        mod_pin=1dbef904f643fcdfdc0055afd3f08aae
        setup_pin=f7ab3a0e1e46b2023684cf835e606d19
    fi
    [ "$(cp_state)" = INSTALLED ] &&
        [ "$(cat "$W/UPGRADE_CURRENT.txt" 2>/dev/null)" = 1.0.0 ] &&
        [ -d "$CP" ] && [ ! -L "$CP" ] &&
        hash_is "$cp_kit/MANIFEST.md5" "$cp_pin" &&
        owned_mod "$cp_kit/payload/mod" "$mod_pin" || return 1
    # An installed CP mod can additionally contain engine-generated .features.
    # Check each signed file against the same verified release the child installed.
    while read -r expected path; do
        case "$path" in payload/mod/*) hash_is "$CP/${path#payload/mod/}" "$expected" || return 1;; esac
    done < "$cp_kit/MANIFEST.md5"
    return 0
}

hash_is "$USB/NEEV_CP_INSTALL.sh" @CP_LAUNCHER@ || fail BAD_CP_INSTALLER 'CarPlay 설치 파일 확인 실패'
# Refuse a damaged mute archive before changing the CP installation.
if ! has_path "$RESTORE" && ! has_path "$PUBLIC/MUTE_1_0_0_DONE"; then
    hash_is "$ZIP" @MUTE_APP_ZIP@ || fail BAD_MUTE_INSTALLER '음소거 설치 파일 확인 실패'
    [ "$(/system/bin/hmgapid version)" = 'hmgapid 1.0.1' ] && [ -x "$CLI" ] || fail WRONG_ENGINE 'HMG-API 1.0.1과 NEMESIS가 필요합니다'
fi
status CP_RUNNING
NEEV_CP_BUNDLE=1 /system/bin/sh "$USB/NEEV_CP_INSTALL.sh"
rc=$?
[ "$rc" = 0 ] || fail CP_FAILED 'CarPlay 업데이트 실패. USB 기록을 확인하세요'
if has_path "$RESTORE" || [ "$(cp_state)" = RESTORED ]; then
    [ "$(cp_state)" = RESTORED ] || fail CP_RECOVERY_INCOMPLETE 'CarPlay 복구 상태를 확인하세요'
    status CP_RESTORED_MUTE_UNCHANGED
    notice 'CarPlay 복구 완료. 내비를 재부팅하세요'
    exit 0
fi
cp_ready || fail CP_STATE_UNVERIFIED 'CarPlay 설치 상태를 확인하세요. 음소거 설치 전 중단했습니다'
[ -d "$PUBLIC" ] && [ ! -L "$W" ] && [ ! -L "$PUBLIC" ] || fail BAD_CP_STAGE 'CarPlay 설치 기록을 확인하세요'
STAGE=$PUBLIC
DONE="$PUBLIC/MUTE_1_0_0_DONE"
if has_path "$DONE"; then
    [ -f "$DONE" ] && [ ! -L "$DONE" ] && [ "$(cat "$DONE")" = "$ADDON 1.0.0" ] || fail BAD_RECEIPT '음소거 설치 기록을 확인하세요'
    # Read only the verified, installed helper. Keep projection metadata off the USB.
    jar="$PUBLIC/kit/payload/setup.jar"
    hash_is "$jar" "$setup_pin" || fail HISTORY_HELPER_INVALID 'CarPlay 설치 유지. 기록 도구를 확인하세요'
    ( export ANDROID_ROOT=/system ANDROID_DATA=/data CLASSPATH="$jar"
      unset LD_PRELOAD HMGAPI_SELFTEST
      exec /system/bin/app_process /system/bin kr.ioniq5.usbcp.setup.Setup history "$OUT" )
    case "$?" in
        0) ;;
        7) status LOGS_EXPORTED_PARTIAL; notice '확인 가능한 CarPlay 기록을 저장했습니다. history-status.txt를 확인하세요'; exit 0;;
        *) fail HISTORY_EXPORT_FAILED 'CarPlay 설치 유지. USB 기록 저장에 실패했습니다';;
    esac
    status LOGS_EXPORTED
    notice 'CarPlay 1.0.0 상태 기록을 USB에 저장했습니다'
    exit 0
fi
hash_is "$ZIP" @MUTE_APP_ZIP@ || fail CP_READY_BAD_MUTE_INSTALLER 'CarPlay 설치됨. 음소거 설치 파일을 확인하세요'
[ "$(/system/bin/hmgapid version)" = 'hmgapid 1.0.1' ] && [ -x "$CLI" ] || fail CP_READY_BAD_ENGINE 'CarPlay 설치됨. HMG-API와 NEMESIS 상태를 확인하세요'
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 1
case "$boot" in ''|*[!a-f0-9-]*) exit 1;; esac
LOCK="$STAGE/.joint-mute-$boot.lock"
mkdir "$LOCK" || fail CP_READY_MUTE_BUSY '음소거 설치가 진행 중입니다. 중단된 경우 내비를 재부팅한 뒤 다시 실행하세요'
trap 'rm -f "$LOCK/done"; rmdir "$LOCK"; sync' EXIT
trap 'exit 1' HUP INT TERM
status CP_READY_MUTE_CHECK
read_registration || fail CP_READY_MUTE_REGISTRY_ERROR 'CarPlay 설치됨. 음소거 패키지 목록 확인 실패'
if has_path "$MOD"; then
    owned_mute || fail CP_READY_MUTE_OTHER_VERSION 'CarPlay 설치됨. 다른 음소거 모드가 설치되어 있습니다'
fi
if has_path "$APP"; then
    owned_app || fail CP_READY_MUTE_OTHER_WRAPPER 'CarPlay 설치됨. 기존 음소거 패키지가 다른 버전입니다'
elif [ "$registered" = 1 ]; then
    fail CP_READY_MUTE_REGISTRY_MISMATCH 'CarPlay 설치됨. 기존 음소거 패키지 등록을 확인하세요'
fi
if [ "$registered" = 0 ]; then
    notice '음소거 일시정지 패키지 설치 중'
    "$CLI" install "$ZIP" || fail CP_READY_MUTE_INSTALL_FAILED 'CarPlay 설치됨. 음소거 설치 실패. 같은 USB로 다시 실행하세요'
elif ! has_path "$MOD"; then
    # A previous postinstall may have failed after the native manager registered
    # its entry. Update the exact verified wrapper only; NEMESIS runs its hooks.
    notice '음소거 일시정지 패키지 설치 재시도'
    "$CLI" install --update "$ZIP" || fail CP_READY_MUTE_REPAIR_FAILED 'CarPlay 설치됨. 음소거 설치 복구 실패'
fi
read_registration && [ "$registered" = 1 ] && owned_app && owned_mute ||
    fail CP_READY_MUTE_UNVERIFIED 'CarPlay 설치됨. 음소거 패키지 확인 실패'
cp_ready || fail CP_CHANGED '두 패키지 처리 중 CarPlay 버전이 변경되었습니다'
echo "$ADDON 1.0.0" > "$LOCK/done" && sync && mv "$LOCK/done" "$DONE" || fail RECEIPT_FAILED '두 패키지 설치 기록을 확인하세요'
if [ -e "$MOD/.disabled" ]; then
    status COMPLETE_MUTE_ALREADY_DISABLED
    notice '두 패키지 설치 완료. 기존 음소거 모드 꺼짐 설정 유지. 내비를 재부팅하세요'
else
    status COMPLETE
    notice 'CarPlay 1.0.0 및 음소거 일시정지 설치 완료. 동글 연결 후 내비를 재부팅하세요'
fi
