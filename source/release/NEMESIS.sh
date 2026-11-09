#!/system/bin/sh
# CarPlay-only installer and history exporter. Optional mods have independent lifecycles.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
umask 077
USB=${0%/*}; [ "$USB" != "$0" ] || USB=$PWD
USB=$(cd "$USB" && pwd -P) || exit 2
W=/data/nemesis/neev-usbcp
CP=/data/hmgapi/mods/neev-usb-carplay
RESTORE="$USB/NEEV-CP-RESTORE"
PUBLIC="$W/public-release-1.0.3"
UUID=@OWNER_UUID@
[ "$(getprop ro.build.product)" = NEEV.KOR.0000.V039.001.260902 ] || exit 2
case "$(id)" in 'uid=0('*|'uid=0 '*|'uid=0') ;; *) exit 2;; esac
# The immutable Setup.jar accepts this historical report-directory prefix.
OUT="$USB/NEEV_BUNDLE_RESULT_$(date +%Y%m%d_%H%M%S)_$$"
mkdir "$OUT" || exit 2
exec > "$OUT/INSTALL.log" 2>&1
@CLI_RUNNER@
@HOOK_DIAGNOSTICS@
hook_diagnostics > "$OUT/HMG_HOOK_STATUS.txt" 2>/dev/null || :
notice() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || :
}
status() { echo "$1" > "$OUT/STATUS.txt" && sync || exit 2; echo "$1"; }
fail() { status "$1"; notice "$2"; exit 1; }
has_path() { [ -e "$1" ] || [ -L "$1" ]; }
hash_is() {
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    checksum=$(md5 "$1") && [ "${checksum%% *}" = "$2" ]
}
owned_mod() {
    [ -d "$1" ] && [ ! -L "$1" ] && [ ! -L "$1/META-HMGAPI" ] &&
        hash_is "$1/META-HMGAPI/manifest.json" "$2" || return 1
    cli_java /system/framework/hmgapi-hook.jar hmgapi.engine.Signing verify "$1" "$UUID"
}
# Read-only equivalent of the original child's startup-registration check.
# Do not start services during log collection: an AP may be intentionally off.
original_startup_registered() {
    has_path "$CP/.disabled" && return 1
    registrations=/data/hmgapi/state/services.txt
    [ -f "$registrations" ] && [ ! -L "$registrations" ] || return 1
    registered_ap=0; registered_provider=0; registered_logger=0
    while read -r service_mod name user start restart kind path cls extra; do
        [ "$service_mod" = neev-usb-carplay ] || continue
        case "$name:$user:$start:$restart:$kind:$path:$cls:$extra" in
            usb-ap:root:boot:yes:script:ap/service.sh:-:)
                [ "$registered_ap" = 0 ] || return 1; registered_ap=1;;
            ap-provider:root:android:yes:jar:provider.jar:kr.ioniq5.usbcp.Provider:)
                [ "$registered_provider" = 0 ] || return 1; registered_provider=1;;
            development-log:root:android:yes:jar:provider.jar:kr.ioniq5.usbcp.DevelopmentLogger:)
                [ "$registered_logger" = 0 ] || return 1; registered_logger=1;;
            *) return 1;;
        esac
    done < "$registrations"
    [ "$registered_ap:$registered_provider:$registered_logger" = 1:1:1 ]
}
original_live_signed() {
    # Match the child's strict signed inventory while permitting only generated
    # .features/.disabled markers. Never alter the installed tree.
    snapshot="$OUT/verified-installed-mod"
    has_path "$snapshot" && return 1
    cp -R "$CP" "$snapshot" || return 1
    for marker in .features .disabled; do
        [ ! -L "$snapshot/$marker" ] || return 1
        if has_path "$snapshot/$marker"; then rm -rf "$snapshot/$marker" || return 1; fi
    done
    owned_mod "$snapshot" "$mod_pin" || return 1
    rm -rf "$snapshot"
}
cp_state() { cat "$PUBLIC/STATUS" 2>/dev/null; }
cp_ready() {
    # The child stages only the pinned manifest members on the head unit.
    # Finder can add ._ sidecars to USB folders. Verify the strict signed local
    # tree, not incidental removable-media metadata; signature checks stay intact.
    cp_kit="$PUBLIC/kit"
    cp_pin=@CP_PAYLOAD_MANIFEST@
    mod_pin=@CP_MOD_MANIFEST@
    setup_pin=@SETUP_JAR@
    original_103=0
    if ! hash_is "$cp_kit/MANIFEST.md5" "$cp_pin" &&
        hash_is "$cp_kit/MANIFEST.md5" f7bba11d2af01840e9887085c2357b3c; then
        [ "$UUID" = bd5f651c-38f9-8ef4-827c-34ad706c2b5c ] || return 1
        original_103=1
        cp_pin=f7bba11d2af01840e9887085c2357b3c
        mod_pin=4d93094965f723ecb20912d45074cfa2
    fi
    for folder in "$W" "$PUBLIC" "$cp_kit"; do
        [ -d "$folder" ] && [ ! -L "$folder" ] || return 1
    done
    [ "$(cp_state)" = INSTALLED ] &&
        [ "$(cat "$W/UPGRADE_CURRENT.txt" 2>/dev/null)" = 1.0.3 ] &&
        [ -d "$CP" ] && [ ! -L "$CP" ] &&
        hash_is "$cp_kit/MANIFEST.md5" "$cp_pin" &&
        owned_mod "$cp_kit/payload/mod" "$mod_pin" || return 1
    # An installed CP mod can additionally contain engine-generated .features.
    # Check each signed file against the same verified release the child installed.
    while read -r expected path; do
        case "$path" in ''|/*|*..*|.*|*/.*) return 1;; esac
        if [ "$original_103" = 1 ]; then
            parent_path=${path%/*}
            while [ "$parent_path" != "$path" ]; do
                [ -d "$cp_kit/$parent_path" ] && [ ! -L "$cp_kit/$parent_path" ] || return 1
                case "$parent_path" in */*) parent_path=${parent_path%/*};; *) break;; esac
            done
            hash_is "$cp_kit/$path" "$expected" || return 1
        fi
        case "$path" in payload/mod/*) hash_is "$CP/${path#payload/mod/}" "$expected" || return 1;; esac
    done < "$cp_kit/MANIFEST.md5"
    return 0
}

hash_is "$USB/NEEV_CP_INSTALL.sh" @CP_LAUNCHER@ || fail BAD_CP_INSTALLER 'CarPlay 설치 파일 확인 실패'
[ "$#" -le 1 ] || fail INVALID_ACTION 'CarPlay 실행 인자를 확인하세요'
case "${1:-}" in
    --install) action=install;;
    ''|--collect) action=collect;;
    *) fail INVALID_ACTION 'CarPlay 실행 인자를 확인하세요';;
esac
before=$(cp_state)
if [ "$action" = collect ] && ! has_path "$RESTORE" && [ "$before" != INSTALLED ]; then
    fail CP_NOT_INSTALLED 'CarPlay 설치 완료 후 기록을 수집하세요'
fi
status CP_RUNNING
# The old signed 1.0.3 runtime is unchanged, but its installer kit has a
# different pin. Authenticate it without executing either generation's installer.
skip_child=0
if [ "$action" = collect ] && ! has_path "$RESTORE" &&
    ! hash_is "$PUBLIC/kit/MANIFEST.md5" @CP_PAYLOAD_MANIFEST@ &&
    hash_is "$PUBLIC/kit/MANIFEST.md5" f7bba11d2af01840e9887085c2357b3c; then
    cp_ready && original_live_signed && original_startup_registered ||
        fail CP_STATE_UNVERIFIED 'CarPlay 설치 상태를 확인하세요'
    skip_child=1
fi
if [ "$skip_child" = 0 ]; then
    # The child uses this flag only to defer its final USB-removal toast.
    NEEV_CP_BUNDLE=1 /system/bin/sh "$USB/NEEV_CP_INSTALL.sh"
    rc=$?
    [ "$rc" = 0 ] || fail CP_FAILED 'CarPlay 업데이트 실패. USB 기록을 확인하세요'
fi
if has_path "$RESTORE" || [ "$(cp_state)" = RESTORED ]; then
    [ "$(cp_state)" = RESTORED ] || fail CP_RECOVERY_INCOMPLETE 'CarPlay 복구 상태를 확인하세요'
    status CP_RESTORED
    notice 'CarPlay 복구 완료. 내비를 재부팅하세요'
    exit 0
fi
cp_ready || fail CP_STATE_UNVERIFIED 'CarPlay 설치 상태를 확인하세요'
if [ "$action" = collect ]; then
    jar="$PUBLIC/kit/payload/setup.jar"
    if [ "$skip_child" = 1 ]; then jar="$USB/NEEV_CP/payload/setup.jar"; fi
    hash_is "$jar" "$setup_pin" || fail HISTORY_HELPER_INVALID 'CarPlay 설치 유지. 기록 도구를 확인하세요'
    cli_java "$jar" kr.ioniq5.usbcp.setup.Setup history "$OUT"
    case "$?" in
        0) ;;
        7) status LOGS_EXPORTED_PARTIAL; notice '확인 가능한 CarPlay 기록을 저장했습니다. history-status.txt를 확인하세요'; exit 0;;
        *) fail HISTORY_EXPORT_FAILED 'CarPlay 설치 유지. USB 기록 저장에 실패했습니다';;
    esac
    status LOGS_EXPORTED
    notice 'CarPlay 1.0.3 상태 기록을 USB에 저장했습니다'
    exit 0
fi
status COMPLETE
notice 'CarPlay 1.0.3 설치 완료. 동글 연결 후 내비를 재부팅하세요'
