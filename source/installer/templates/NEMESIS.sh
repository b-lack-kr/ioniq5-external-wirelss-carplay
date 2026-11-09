#!/system/bin/sh
# Bootstrap the local transaction from an authenticated USB tree. No personal data is exported.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
unset LD_PRELOAD HMGAPI_SELFTEST
umask 077
USB=${0%/*}; [ "$USB" != "$0" ] || USB=$PWD
USB=$(cd "$USB" && pwd -P) || exit 2
W=/data/nemesis/neev-usbcp
R=$W/public-release-1.0.3
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
@CLI_RUNNER@
@HOOK_DIAGNOSTICS@
hook_diagnostics > "$OUT/HMG_HOOK_STATUS.txt" 2>/dev/null || :
notice() { echo "$1"; am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || :; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
absent() { [ ! -e "$1" ] && [ ! -L "$1" ]; }
dir() { [ -d "$1" ] && [ ! -L "$1" ]; }
hash() { set -- $(md5 "$1" 2>/dev/null); echo "$1"; }
stop() { echo "$1" > "$OUT/STATUS.txt"; sync; notice "CarPlay 설치 중단: ${2:-$1}"; exit 4; }
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
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 2
case "$boot" in ''|*[!a-f0-9-]*) exit 2;; esac
check_kit() {
    dir "$1" && regular "$1/MANIFEST.md5" && [ "$(hash "$1/MANIFEST.md5")" = "${2:-$MANIFEST}" ] || return 1
    while read digest path; do
        case "$path" in ''|/*|*..*|.*|*/.*) return 1;; esac
        regular "$1/$path" && [ "$(hash "$1/$path")" = "$digest" ] || return 1
    done < "$1/MANIFEST.md5"
}
# A rerun or restore executes only this exact release's verified transaction.
check_recorded_kit() { check_kit "$R/kit"; }
# Exact public 1.0.1 USB, SHA256 17f565786fba31b93ad38d5d5a5f383b9f8fe7d1fab1b9368baa8a08de3167c9.
# Authenticate its complete local kit, but never execute its old scripts.
check_101_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 4878be51c37df38b5d1b833c90278342
}
# Public 1.0.2 is distinct from the historical internal 1.0.2 transaction.
# Final published USB SHA256 eb2f41243987676b7a3d81e8096be6d67ab1da2e218f2c1d5d3a2be90489d409.
# Authenticate all retained kit bytes and the signed mod before parking its history.
check_public_release_102_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 97bf7ec190f1d9d7ed1474f1779a678a &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = 3506468513d6f43f454d9ba019c71de3 ]
}
# Original public 1.0.3 before the installer-only revision. Its complete kit is
# accepted only for authenticated retirement, never execution under new rules.
# Core USB SHA256 76174dfab95ae3a8b97e7f0a6ce492ec49a507194e9135103deef90dd29e8ef2.
check_original_public_103_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" f7bba11d2af01840e9887085c2357b3c &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = 4d93094965f723ecb20912d45074cfa2 ]
}
check_retired_public_103_kit() {
    check_recorded_kit || check_original_public_103_kit "$R/kit"
}
# Exact installed 1.0.2 transaction from the map-repair USB build.
# USB SHA256 f2a1db15cb93cf0625059211087f1c84318bdbde7b9b358a1e05034b4a078d95.
check_102_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 4393fb985779e1adeb96a9e8a2b8ee7a
}
# Exact released 1.0.3 transaction from the final signed build.
# USB SHA256 b720bb117309937e9d860cee1347eba991d8a01b0c972455cd15dfe8070ad219.
check_103_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 1b8b5e86837c69f0521e0cb9bb2ef1e7
}
# Exact 1.0.4 signed transactions: final-r2/automatic USB, and initial final build.
# Each kit is bound to its own mod manifest; arbitrary same-version bytes are rejected.
check_104_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] || return 1
    case "$(hash "$1/MANIFEST.md5")" in
        83291aab67a4137f5dc537484c99e95e) old104_kit_md5=83291aab67a4137f5dc537484c99e95e; old104_mod_md5=6a919ac761d1f52d0067c7dfe31052a6;;
        8786d5338db1d63ecabd9b975871adff) old104_kit_md5=8786d5338db1d63ecabd9b975871adff; old104_mod_md5=13866b32401b2684a927206b8a0d2c8c;;
        *) return 1;;
    esac
    check_kit "$1" "$old104_kit_md5" &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = "$old104_mod_md5" ]
}
# Exact installed 1.0.5 signed transaction, including the USB shell compatibility fix.
# Core USB SHA256 43f99958024d6bf5b1c949946fb4ac8bb6b120bd6ffc4a669c5c50f28b20a2ab.
# The wrapper-only fix left this kit/mod pair unchanged.
check_105_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 9d0e922345184343d7c5c453200b8193 &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = ed448f1ff1b2271cb94faca84d9c9b83 ]
}
# Exact installed 1.0.6 signed transaction (host-verified candidate).
# Core USB SHA256 626fedc17492d1aa9d489b647842d7d615d6497a73144a8af566eee49d0940aa.
check_106_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 2c6692b98c3dc8aaa04a29e8936b1c34 &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = 996a307153f0a2bdfc5379235d386e7e ]
}

# Exact installed 1.0.7 signed transaction (host-verified candidate).
# Core USB SHA256 4a2253a8ea351a145ab8742a092b878bd1f559d181a138a9b33ebc71cc5ffb3c.
check_107_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 51867b7e289e9f48b21cf9c5ed456303 &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = 2bd01e8dfdee15774e003fb9c4eca170 ]
}
# Exact complete 1.0.9 USB release. Keep its original transaction intact.
# USB SHA256 0eb75dc5798888669cc44df2edc034a5ea488557e4829253472b3e05925ba373.
check_109_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" ff621830b3be5f168de0eb1d6eb97207 &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = a4f9facdf98a3576b0221e06fdc88fbd ]
}
# Exact complete 1.0.10 USB release; retain its verified transaction for rollback.
# USB SHA256 6b1d0798faa69601cbd264626238c2c100d4335c6f838128b4a81f0e31bad160.
check_110_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 208ddce323c19685b0d0ecd36757d272 &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = 9d376dea3f6253484996c340e471983d ]
}
# Exact complete 1.0.11 USB release; keep its original kit and rollback record.
# USB SHA256 34f4f5037c5e622c41a03b61ac8d807a805d5099349239c0a738781aef1f3ca4.
check_111_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 7316d7b1231cab6ac76ae429541bad1d &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = 5290d4f672e0afac9522dc36a190c4cf ]
}
# Exact complete 1.0.12 USB release; keep its original kit and rollback record.
# USB SHA256 f9809de66b866592539d4aa489ebb6d3e7fe7e0d00aaabaf9208582e5482f2da.
check_112_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 0e187a3871ec165fabf132d685f4be7a &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = 8b19ded5ac2b93da86a0a65eeb9fb73e ]
}
# Exact complete 1.0.13 USB release; keep its original kit and rollback record.
# USB SHA256 c6ba250a209dcc33258a80420ba06bf1c72299ae4e82c069d98801308dc9d7b9.
check_113_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 09b1079e662fe4b3a8414c4dbf80045e &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = e84dcee836c98ef74e7d0be8a8e6bc11 ]
}
# Exact complete 1.0.14 USB release; keep its original kit and rollback record.
# USB SHA256 3d78e92c928d745704275cde94006e1eaaef947254c1d0e30329fda048bd10c7.
check_114_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" aaf0268ce3ceddaca1fcf4ab79d0daab &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = e0f3a6b8e3206d1bbb9f5c1597033084 ]
}
# Authenticated internal v1.0.15 may be retired after manual Uninstall.
# Its runtime is the baseline of this public release; do not execute the old kit.
# USB SHA256 3608c6659d4c378ffc84ca19d27a233580c022d19e8939326fc45c516596c6e5.
check_115_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] &&
        check_kit "$1" 9abbedc9f118a9334718a7b4ee7ef668 &&
        regular "$1/payload/mod/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/payload/mod/META-HMGAPI/manifest.json")" = c8a0de0f5a9cfc4a833680aca232a713 ]
}
# Completed 1.0.0 installs can be parked after the owner removes the mod.
# Do not execute their old scripts: verify the complete pinned kit and signature.
check_removed_100_kit() {
    [ "@OWNER@" = "bd5f651c-38f9-8ef4-827c-34ad706c2b5c" ] || return 1
    regular "$1/MANIFEST.md5" || return 1
    case "$(hash "$1/MANIFEST.md5")" in
        70667411818e14d545a013085330c391) check_kit "$1" 70667411818e14d545a013085330c391;;
        38eac52adfc6d6d719e64c1da2957dbf) check_kit "$1" 38eac52adfc6d6d719e64c1da2957dbf;;
        *) return 1;;
    esac
}
check_kit "$PACK" || stop INVALID_USB_PACKAGE
hmg_api_ready || stop "$HMG_STATUS" "$HMG_NOTICE"
dir /data/nemesis || stop NEMESIS_NOT_READY 'NEMESIS 데이터 폴더가 없습니다. NEMESIS 설치 상태를 확인하세요'
dir "$H" && dir "$H/maps" && dir "$H/mods" && dir "$H/hook" && dir "$H/state" || stop HMG_API_NOT_READY 'HMG-API 데이터 폴더를 확인하세요. HMG-API 설치 상태가 준비되지 않았습니다'
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
    # Fixed diagnostic paths only; never export AP configuration or identity.
    # Android 4.4 toolbox dd keeps each report bounded to 64 KiB.
    for log in map-before.log map.log verify-map.log plan-before.log; do
        if regular "$R/$log"; then dd if="$R/$log" of="$OUT/$log" bs=1024 count=64 2>/dev/null || :; fi
    done
    if [ "$rc" = 0 ]; then echo COMPLETE > "$OUT/STATUS.txt"; elif [ ! -s "$OUT/STATUS.txt" ]; then echo "FAILED rc=$rc" > "$OUT/STATUS.txt"; fi
    rm -rf "$LOCK/previous-mod"; rm -f "$LOCK/after-remove-plan.txt"; rmdir "$LOCK"; sync; exit "$rc"
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
acquire_legacy_lock() {
    dir "$W" && mkdir "$W/v017.lock" || stop LEGACY_UPDATER_BUSY
    legacy_lock=1
    echo "$boot $$" > "$W/v017.lock/owner" || stop LOCK_WRITE
}
# Engine.remove and NEMESIS uninstall remove the mod, but do not own the old
# USB workspace or its preferences. Retire only a verified completed install.
verify_removed_source() {
    dir "$1" && dir "$1/META-HMGAPI" && regular "$1/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/META-HMGAPI/manifest.json")" = "$2" ] || return 1
    cli_java /system/framework/hmgapi-hook.jar hmgapi.engine.Signing verify "$1" "@OWNER@"
}
verify_removed_workspace() {
    regular "$W/UPGRADE_CURRENT.txt" || return 1
    version=$(cat "$W/UPGRADE_CURRENT.txt")
    # Same visible version as an old internal build is not sufficient proof.
    if { [ "$version" = 1.0.3 ] || [ "$version" = none ]; } && ! absent "$R"; then
        record=$R
        dir "$record" && check_retired_public_103_kit && regular "$record/STATUS" &&
            regular "$record/mode" || return 1
        if [ "$version" = none ]; then
            [ "$(cat "$record/STATUS")" = RESTORED ] &&
                [ "$(cat "$record/mode")" = fresh ] || return 1
        else
            [ "$(cat "$record/STATUS")" = INSTALLED ] || return 1
            case "$(cat "$record/mode")" in fresh|1.0.1) ;; *) return 1;; esac
        fi
        if check_recorded_kit; then retired_manifest=@PUBLIC_MOD_MD5@
        else retired_manifest=4d93094965f723ecb20912d45074cfa2; fi
        verify_removed_source "$record/kit/payload/mod" "$retired_manifest"
        return
    fi
    # A completed public 1.0.2 uninstall has its own independently pinned kit.
    # Never fall back to the internal record if that public record is damaged.
    if [ "$version" = 1.0.2 ] && ! absent "$W/public-release-1.0.2"; then
        absent "$R" || return 1
        record=$W/public-release-1.0.2
        dir "$record" && check_public_release_102_kit "$record/kit" &&
            regular "$record/STATUS" && [ "$(cat "$record/STATUS")" = INSTALLED ] &&
            regular "$record/mode" || return 1
        case "$(cat "$record/mode")" in fresh|1.0.1) ;; *) return 1;; esac
        verify_removed_source "$record/kit/payload/mod" 3506468513d6f43f454d9ba019c71de3
        return
    fi
    case "$version" in
        1.0.15|1.0.14|1.0.13|1.0.12|1.0.11|1.0.10|1.0.9|1.0.7|1.0.6|1.0.5|1.0.4|1.0.3|1.0.2|1.0.1)
            # A completed rollback may remain next to its still-authenticated base.
            if ! absent "$R"; then
                dir "$R" && check_recorded_kit && regular "$R/STATUS" &&
                    [ "$(cat "$R/STATUS")" = RESTORED ] && regular "$R/mode" &&
                    [ "$(cat "$R/mode")" = "$version" ] || return 1
            fi
            record=$W/public-$version
            dir "$record" || return 1
            case "$version" in
                1.0.15) check_115_kit "$record/kit" || return 1;;
                1.0.14) check_114_kit "$record/kit" || return 1;;
                1.0.13) check_113_kit "$record/kit" || return 1;;
                1.0.12) check_112_kit "$record/kit" || return 1;;
                1.0.11) check_111_kit "$record/kit" || return 1;;
                1.0.10) check_110_kit "$record/kit" || return 1;;
                1.0.9) check_109_kit "$record/kit" || return 1;;
                1.0.7) check_107_kit "$record/kit" || return 1;;
                1.0.6) check_106_kit "$record/kit" || return 1;;
                1.0.5) check_105_kit "$record/kit" || return 1;;
                1.0.4) check_104_kit "$record/kit" || return 1;;
                1.0.3) check_103_kit "$record/kit" || return 1;;
                1.0.2) check_102_kit "$record/kit" || return 1;;
                1.0.1) check_101_kit "$record/kit" || return 1;;
            esac;;
        1.0.0)
            absent "$R" || return 1
            record=$W/public-1.0.0
            dir "$record" && check_removed_100_kit "$record/kit" || return 1;;
        *) record=;;
    esac
    if [ -n "$record" ]; then
        regular "$record/STATUS" && [ "$(cat "$record/STATUS")" = INSTALLED ] &&
            regular "$record/mode" && dir "$record/kit/payload" || return 1
        case "$(cat "$record/mode")" in
            'fresh'|'0.1.10'|'0.1.11'|'1.0.1'|'1.0.2'|'1.0.3'|'1.0.4'|'1.0.5'|'1.0.6'|'1.0.7') ;;
            '1.0.9') { [ "$version" = 1.0.10 ] || [ "$version" = 1.0.11 ] || [ "$version" = 1.0.12 ] || [ "$version" = 1.0.13 ] || [ "$version" = 1.0.14 ] || [ "$version" = 1.0.15 ]; } || return 1;;
            '1.0.10') { [ "$version" = 1.0.11 ] || [ "$version" = 1.0.12 ] || [ "$version" = 1.0.13 ] || [ "$version" = 1.0.14 ] || [ "$version" = 1.0.15 ]; } || return 1;;
            '1.0.11') { [ "$version" = 1.0.12 ] || [ "$version" = 1.0.13 ] || [ "$version" = 1.0.14 ] || [ "$version" = 1.0.15 ]; } || return 1;;
            '1.0.12') { [ "$version" = 1.0.13 ] || [ "$version" = 1.0.14 ] || [ "$version" = 1.0.15 ]; } || return 1;;
            '1.0.13') { [ "$version" = 1.0.14 ] || [ "$version" = 1.0.15 ]; } || return 1;;
            '1.0.14') [ "$version" = 1.0.15 ] || return 1;;
            *) return 1;;
        esac
        verify_removed_source "$record/kit/payload/mod" "$(hash "$record/kit/payload/mod/META-HMGAPI/manifest.json")"
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
    cli_java /system/framework/hmgapi-hook.jar hmgapi.engine.Engine plan > "$LOCK/after-remove-plan.txt" 2>&1 || stop APPLY_CHANGES_AFTER_REMOVE
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
# Never replace a different transaction under this release's path.
if dir "$R" && ! check_recorded_kit; then
    [ "$restore" = 0 ] && absent "$MOD" && check_original_public_103_kit "$R/kit" || stop LOCAL_TRANSACTION_DAMAGED
fi
if [ "$restore" = 0 ] && dir "$W" && absent "$MOD"; then
    if absent "$R" || { dir "$R" && regular "$R/STATUS" &&
        { [ "$(cat "$R/STATUS")" = INSTALLED ] ||
          { [ "$(cat "$R/STATUS")" = RESTORED ] && regular "$W/UPGRADE_CURRENT.txt" && { [ "$(cat "$W/UPGRADE_CURRENT.txt")" = none ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.1 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.2 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.3 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.4 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.5 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.6 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.7 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.9 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.10 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.11 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.12 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.13 ] || [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.14 ]; }; }; }; }; then
        retire_removed_workspace
    fi
fi
if ! dir "$R"; then
    [ "$restore" = 0 ] || stop NO_PUBLIC_INSTALL
    # Only the published 1.0.1 has a direct upgrade contract. Internal 1.0.2
    # through 1.0.15 share public version strings but are not this release.
    if dir "$MOD" && regular "$W/UPGRADE_CURRENT.txt"; then
        case "$(cat "$W/UPGRADE_CURRENT.txt")" in
            1.0.1) ;;
            1.0.2)
                if ! absent "$W/public-release-1.0.2"; then
                    stop UNINSTALL_PREVIOUS_RELEASE 'CarPlay 1.0.2를 NEMESIS에서 삭제한 뒤 1.0.3을 설치하세요'
                fi
                stop UNINSTALL_INTERNAL_TEST_VERSION '기존 CarPlay 패키지를 NEMESIS에서 삭제한 뒤 1.0.3을 설치하세요';;
            *) stop UNINSTALL_INTERNAL_TEST_VERSION '기존 CarPlay 패키지를 NEMESIS에서 삭제한 뒤 1.0.3을 설치하세요';;
        esac
    fi
    if dir "$MOD" && regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.0 ]; then
        stop UNINSTALL_1_0_0_FIRST
    fi
    if absent "$W"; then
        absent "$MOD" || stop UNOWNED_INSTALLED_MOD
        mode=fresh
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.14 ]; then
        previous=$W/public-1.0.14
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_114_kit "$previous/kit" || stop UNOWNED_PUBLIC_114
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5|1.0.6|1.0.7|1.0.9|1.0.10|1.0.11|1.0.12|1.0.13) ;; *) stop UNOWNED_PUBLIC_114;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = e0f3a6b8e3206d1bbb9f5c1597033084 ] &&
            verify_removed_source "$previous/kit/payload/mod" e0f3a6b8e3206d1bbb9f5c1597033084 || stop UNOWNED_PUBLIC_114
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.14
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.13 ]; then
        previous=$W/public-1.0.13
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_113_kit "$previous/kit" || stop UNOWNED_PUBLIC_113
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5|1.0.6|1.0.7|1.0.9|1.0.10|1.0.11|1.0.12) ;; *) stop UNOWNED_PUBLIC_113;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = e84dcee836c98ef74e7d0be8a8e6bc11 ] &&
            verify_removed_source "$previous/kit/payload/mod" e84dcee836c98ef74e7d0be8a8e6bc11 || stop UNOWNED_PUBLIC_113
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.13
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.12 ]; then
        previous=$W/public-1.0.12
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_112_kit "$previous/kit" || stop UNOWNED_PUBLIC_112
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5|1.0.6|1.0.7|1.0.9|1.0.10|1.0.11) ;; *) stop UNOWNED_PUBLIC_112;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = 8b19ded5ac2b93da86a0a65eeb9fb73e ] &&
            verify_removed_source "$previous/kit/payload/mod" 8b19ded5ac2b93da86a0a65eeb9fb73e || stop UNOWNED_PUBLIC_112
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.12
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.11 ]; then
        previous=$W/public-1.0.11
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_111_kit "$previous/kit" || stop UNOWNED_PUBLIC_111
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5|1.0.6|1.0.7|1.0.9|1.0.10) ;; *) stop UNOWNED_PUBLIC_111;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = 5290d4f672e0afac9522dc36a190c4cf ] &&
            verify_removed_source "$previous/kit/payload/mod" 5290d4f672e0afac9522dc36a190c4cf || stop UNOWNED_PUBLIC_111
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.11
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.10 ]; then
        previous=$W/public-1.0.10
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_110_kit "$previous/kit" || stop UNOWNED_PUBLIC_110
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5|1.0.6|1.0.7|1.0.9) ;; *) stop UNOWNED_PUBLIC_110;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = 9d376dea3f6253484996c340e471983d ] &&
            verify_removed_source "$previous/kit/payload/mod" 9d376dea3f6253484996c340e471983d || stop UNOWNED_PUBLIC_110
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.10
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.9 ]; then
        previous=$W/public-1.0.9
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_109_kit "$previous/kit" || stop UNOWNED_PUBLIC_109
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5|1.0.6|1.0.7) ;; *) stop UNOWNED_PUBLIC_109;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = a4f9facdf98a3576b0221e06fdc88fbd ] &&
            verify_removed_source "$previous/kit/payload/mod" a4f9facdf98a3576b0221e06fdc88fbd || stop UNOWNED_PUBLIC_109
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.9
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.7 ]; then
        previous=$W/public-1.0.7
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_107_kit "$previous/kit" || stop UNOWNED_PUBLIC_107
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5|1.0.6) ;; *) stop UNOWNED_PUBLIC_107;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = 2bd01e8dfdee15774e003fb9c4eca170 ] &&
            verify_removed_source "$previous/kit/payload/mod" 2bd01e8dfdee15774e003fb9c4eca170 || stop UNOWNED_PUBLIC_107
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.7
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.6 ]; then
        previous=$W/public-1.0.6
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_106_kit "$previous/kit" || stop UNOWNED_PUBLIC_106
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4|1.0.5) ;; *) stop UNOWNED_PUBLIC_106;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = 996a307153f0a2bdfc5379235d386e7e ] &&
            verify_removed_source "$previous/kit/payload/mod" 996a307153f0a2bdfc5379235d386e7e || stop UNOWNED_PUBLIC_106
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.6
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.5 ]; then
        previous=$W/public-1.0.5
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_105_kit "$previous/kit" || stop UNOWNED_PUBLIC_105
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3|1.0.4) ;; *) stop UNOWNED_PUBLIC_105;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = ed448f1ff1b2271cb94faca84d9c9b83 ] &&
            verify_removed_source "$previous/kit/payload/mod" ed448f1ff1b2271cb94faca84d9c9b83 || stop UNOWNED_PUBLIC_105
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.5
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.4 ]; then
        previous=$W/public-1.0.4
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_104_kit "$previous/kit" || stop UNOWNED_PUBLIC_104
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2|1.0.3) ;; *) stop UNOWNED_PUBLIC_104;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = "$old104_mod_md5" ] &&
            verify_removed_source "$previous/kit/payload/mod" "$old104_mod_md5" || stop UNOWNED_PUBLIC_104
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.4
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.3 ]; then
        previous=$W/public-1.0.3
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_103_kit "$previous/kit" || stop UNOWNED_PUBLIC_103
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1|1.0.2) ;; *) stop UNOWNED_PUBLIC_103;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = 8619831be09d30c44d02a6066defe0e4 ] &&
            verify_removed_source "$previous/kit/payload/mod" 8619831be09d30c44d02a6066defe0e4 || stop UNOWNED_PUBLIC_103
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.3
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.2 ]; then
        previous=$W/public-1.0.2
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_102_kit "$previous/kit" || stop UNOWNED_PUBLIC_102
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11|1.0.1) ;; *) stop UNOWNED_PUBLIC_102;; esac
        dir "$MOD" && dir "$MOD/META-HMGAPI" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = 3b26a09dd4909ab82fa9837d7956f5de ] &&
            verify_removed_source "$previous/kit/payload/mod" 3b26a09dd4909ab82fa9837d7956f5de || stop UNOWNED_PUBLIC_102
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.2
    elif regular "$W/UPGRADE_CURRENT.txt" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.1 ]; then
        previous=$W/public-1.0.1
        dir "$W" && dir "$previous" && regular "$previous/STATUS" &&
            [ "$(cat "$previous/STATUS")" = INSTALLED ] && regular "$previous/mode" &&
            check_101_kit "$previous/kit" || stop UNOWNED_PUBLIC_101
        case "$(cat "$previous/mode")" in fresh|0.1.10|0.1.11) ;; *) stop UNOWNED_PUBLIC_101;; esac
        dir "$MOD" && regular "$MOD/META-HMGAPI/manifest.json" &&
            [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = 68433b6b1343aebdebfa94a488b7fc06 ] &&
            verify_removed_source "$previous/kit/payload/mod" 68433b6b1343aebdebfa94a488b7fc06 || stop UNOWNED_PUBLIC_101
        absent "$MOD/.disabled" || stop EXISTING_MOD_DISABLED
        mode=1.0.1
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
        absent "$FRESH" && mkdir "$FRESH" && mv "$STAGE" "$FRESH/public-release-1.0.3" && absent "$W" && mv "$FRESH" "$W" || stop STAGING
    else mv "$STAGE" "$R" || stop STAGING; fi
fi
check_recorded_kit && regular "$R/mode" && regular "$R/STATUS" || stop LOCAL_TRANSACTION_DAMAGED
if [ "$legacy_lock" != 1 ]; then acquire_legacy_lock; fi
U=$R/kit
. "$U/operate.sh"
