#!/system/bin/sh
# Installer-only Java runner. Never source this in long-lived projection services.
# The caller validates the stock executable and payload before invoking this helper.
cli_java() (
    [ "$#" -ge 2 ] || { echo 'CLI_REJECT invalid_call' >&2; return 64; }
    cli_classpath=$1
    cli_class=$2
    shift 2
    case "$cli_class" in
        kr.ioniq5.usbcp.setup.Setup)
            cli_label=Setup
            case "${1-}" in history|firmware|merge|config|uid|tree|same-tree) cli_operation=$1;;
                *) echo 'CLI_REJECT invalid_operation' >&2; return 64;; esac;;
        kr.ioniq5.publiccp.VendorAssets)
            cli_label=VendorAssets; cli_operation=import;;
        kr.ioniq5.usbcp.ApIdentity)
            cli_label=ApIdentity
            case "${1-}" in preflight|apply) cli_operation=$1;;
                *) echo 'CLI_REJECT invalid_operation' >&2; return 64;; esac;;
        hmgapi.engine.Engine)
            cli_label=Engine
            case "${1-}" in plan|map|recover|remove|apply|install|verify-map) cli_operation=$1;;
                *) echo 'CLI_REJECT invalid_operation' >&2; return 64;; esac;;
        hmgapi.engine.Signing)
            cli_label=Signing
            case "${1-}" in verify) cli_operation=$1;;
                *) echo 'CLI_REJECT invalid_operation' >&2; return 64;; esac;;
        *) echo 'CLI_REJECT invalid_class' >&2; return 64;;
    esac
    cli_mode=$(getprop dalvik.vm.execution-mode 2>/dev/null) || cli_mode=unavailable
    case "$cli_mode" in '') cli_mode=default;; int:fast|int:portable|int:jit) ;; *) cli_mode=other;; esac
    cli_extra=$(getprop dalvik.vm.extra-opts 2>/dev/null) || cli_extra=
    case "$cli_extra" in '') cli_extra_state=absent;; *) cli_extra_state=present;; esac
    # Do not expose classpaths, file paths, identities, credentials or raw arguments.
    echo "CLI_BEGIN class=$cli_label operation=$cli_operation vm_mode=$cli_mode extra_opts=$cli_extra_state" >&2
    if (
        unset LD_PRELOAD HMGAPI_SELFTEST
        export CLASSPATH="$cli_classpath" ANDROID_ROOT=/system ANDROID_DATA=/data
        exec /system/bin/app_process.hmgapi-stock -Xint /system/bin "$cli_class" "$@"
    ); then
        cli_rc=0
    else
        cli_rc=$?
    fi
    echo "CLI_END class=$cli_label operation=$cli_operation rc=$cli_rc" >&2
    return "$cli_rc"
)
