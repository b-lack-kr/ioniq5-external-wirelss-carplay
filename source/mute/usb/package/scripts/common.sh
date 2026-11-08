# Sourced by NEMESIS package hooks with the package root as cwd.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
umask 077
ID=neev-carplay-mute-pause
MOD=/data/hmgapi/mods/neev-carplay-mute-pause
H=/system/bin/hmgapid
UUID=@AUTHOR_UUID@

fail() { echo "CarPlay Mute Pause: $*" >&2; exit 1; }
hash_is() {
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    digest=$(md5 "$1") || return 1
    [ "${digest%% *}" = "$2" ]
}
verify_installed() {
    ( export CLASSPATH=/system/framework/hmgapi-hook.jar ANDROID_ROOT=/system ANDROID_DATA=/data
      unset HMGAPI_SELFTEST
      exec /system/bin/app_process /system/bin hmgapi.engine.Signing verify "$MOD" "$UUID" )
}
current_release() {
    [ -d "$MOD" ] && [ ! -L "$MOD" ] && [ ! -L "$MOD/META-HMGAPI" ] &&
        hash_is "$MOD/META-HMGAPI/manifest.json" @MOD_MANIFEST_MD5@ && verify_installed
}
installed_release() {
    [ -d "$MOD" ] && [ ! -L "$MOD" ] && [ ! -L "$MOD/META-HMGAPI" ] || return 1
    if ! hash_is "$MOD/META-HMGAPI/manifest.json" @MOD_MANIFEST_MD5@; then
        # Only our exact previously released 1.0.0 builds may be replaced.
        [ "$UUID" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] || return 1
        hash_is "$MOD/META-HMGAPI/manifest.json" 3152f3e47803c0cdc6081b27015fd1ee ||
            hash_is "$MOD/META-HMGAPI/manifest.json" 90bfe998c4bcd2ec1dc173b31de5b73d || return 1
    fi
    verify_installed
}
has_installed() { [ -e "$MOD" ] || [ -L "$MOD" ]; }
# HMG-API's minimum version is independent of the pinned CarPlay release records.
# Parse canonical decimal components without shell arithmetic (overflow/octal safe).
hmg_api_error() { HMG_STATUS=$1; HMG_NOTICE=$2; return 1; }
hmg_api_ready() {
    [ -x /system/bin/hmgapid ] || { hmg_api_error HMG_API_MISSING 'HMG-API 실행 파일이 없습니다. HMG-API 설치를 확인하세요'; return 1; }
    hmg_version=$(/system/bin/hmgapid version 2>/dev/null) || { hmg_api_error HMG_API_VERSION_UNAVAILABLE 'HMG-API 버전을 읽지 못했습니다. HMG-API 설치 상태를 확인하세요'; return 1; }
    case "$hmg_version" in
        'hmgapid '*) hmg_version=${hmg_version#hmgapid };;
        *) hmg_api_error HMG_API_VERSION_INVALID 'HMG-API 버전 형식을 확인할 수 없습니다. 정식 버전 설치를 확인하세요'; return 1;;
    esac
    case "$hmg_version" in
        ''|*[!0-9.]*|*.*.*.*) hmg_api_error HMG_API_VERSION_INVALID 'HMG-API 버전 형식을 확인할 수 없습니다. 정식 버전 설치를 확인하세요'; return 1;;
        *.*.*) ;;
        *) hmg_api_error HMG_API_VERSION_INVALID 'HMG-API 버전 형식을 확인할 수 없습니다. 정식 버전 설치를 확인하세요'; return 1;;
    esac
    hmg_major=${hmg_version%%.*}; hmg_rest=${hmg_version#*.}
    hmg_minor=${hmg_rest%%.*}; hmg_patch=${hmg_rest#*.}
    for hmg_part in "$hmg_major" "$hmg_minor" "$hmg_patch"; do
        case "$hmg_part" in
            ''|0?*) hmg_api_error HMG_API_VERSION_INVALID 'HMG-API 버전 형식을 확인할 수 없습니다. 정식 버전 설치를 확인하세요'; return 1;;
        esac
    done
    # This is only the declared minimum. Signing, engine and map checks still run.
    if [ "$hmg_major" = 0 ] || { [ "$hmg_major" = 1 ] && [ "$hmg_minor" = 0 ] && [ "$hmg_patch" = 0 ]; }; then
        hmg_api_error HMG_API_TOO_OLD 'HMG-API 1.0.1 이상이 필요합니다. HMG-API를 업데이트하세요'; return 1
    fi
    /system/bin/hmgapid ping >/dev/null 2>&1 || { hmg_api_error HMG_API_DAEMON_UNAVAILABLE 'HMG-API 데몬이 응답하지 않습니다. 내비 재부팅 후 다시 실행하세요'; return 1; }
}
engine_ready() {
    hmg_api_ready || fail "$HMG_STATUS" "$HMG_NOTICE"
    [ ! -e /data/hmgapi/state/journal.json ] || fail UNFINISHED_HMG_TRANSACTION
}
check_install() {
    [ "$(getprop ro.build.product)" = NEEV.KOR.0000.V039.001.260902 ] &&
        [ "$(getprop ro.board.platform)" = tcc803x ] || fail WRONG_HEAD_UNIT
    [ "$(getprop sys.boot_completed)" = 1 ] || fail WAIT_FOR_BOOT
    hash_is /system/app/HKMC_DiPO.odex 3b26cdcbf3c43f75623311f2bdfc3471 || fail WRONG_DIPO_BUILD
    [ ! -L mod ] && [ "$(cat mod/id)" = "$ID" ] && hash_is mod/package.zip @PACKAGE_ZIP_MD5@ || fail PACKAGE_MISMATCH
    engine_ready
    map=$("$H" engine map) || fail HOOK_MAP_READ_FAILED
    case "$map" in *'hooks on'*) ;; *) fail HOOK_MAP_DISABLED;; esac
    if has_installed; then installed_release || fail EXISTING_MOD_MISMATCH; fi
}
