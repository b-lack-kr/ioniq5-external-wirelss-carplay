#!/system/bin/sh
# Runtime helpers for the owned USB AP. No actions happen merely by sourcing.
ap_notice() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || :
}
ap_phase() {
    case "$1" in ''|*[!A-Z0-9_]*) return 1;; esac
    [ "${AP_PHASE:-}" = "$1" ] && return 0
    AP_PHASE=$1
    echo "STATE=$AP_PHASE" > "$TMP_DIR/service-state.txt"
    echo "AP_EVENT state=$AP_PHASE"
    if [ "$AP_PHASE" = WAIT_DONGLE ] || [ "$AP_PHASE" = SERVICES_RUNNING ]; then
        AP_FAILURE_NOTIFIED=0
        return 0
    fi
    local failures=" DRIVER_FAILED VENDOR_ASSETS_INVALID HOSTAPD_EXITED DNSMASQ_EXITED STOP_TIMEOUT IDENTITY_REJECTED HOSTAPD_PATCH_FAILED NETWORK_SETUP_FAILED AP_PERMISSION_FAILED CONFIG_MISSING CONFIG_INVALID "
    case "$failures" in
        *" $AP_PHASE "*)
            if [ "${AP_FAILURE_NOTIFIED:-0}" = 0 ]; then
                AP_FAILURE_NOTIFIED=1
                ap_notice "USB Wi-Fi 시작 실패: $AP_PHASE"
            fi;;
    esac
}
ap_guard() {
    CLASSPATH="$APP_DIR/../provider.jar" /system/bin/app_process /system/bin kr.ioniq5.usbcp.SafeInterface "$@"
}
ap_select() {
    local selection
    selection=$(ap_guard) || return 1
    set -- $selection
    [ "$#" = 3 ] || return 1
    case "$1" in rtwlan[0-9]*) ;; *) return 1;; esac
    case "$2" in ''|*[!0-9]*) return 1;; esac
    case "$3" in ''|*[!0-9a-f]*) return 1;; esac
    [ "${#3}" = 64 ] || return 1
    SELECT_IFACE=$1; SELECT_INDEX=$2; SELECT_TOKEN=$3
}
ap_active_identity() {
    [ -n "$ACTIVE_IFACE" ] && [ -n "$ACTIVE_TOKEN" ] || return 1
    ap_guard check "$ACTIVE_IFACE" "$ACTIVE_TOKEN" >/dev/null 2>&1
}
ap_valid_pid() {
    case "$1" in ''|*[!0-9]*) return 1;; esac
    [ "${#1}" -le 9 ] && [ "$1" -gt 1 ]
}
ap_child_live() {
    local key value rest parent='' state=''
    ap_valid_pid "$1" || return 1
    [ -r "/proc/$1/status" ] || return 1
    while read -r key value rest; do
        case "$key" in PPid:) parent=$value;; State:) state=$value;; esac
    done < "/proc/$1/status"
    [ "$parent" = "$$" ] && [ "$state" != Z ] && [ -n "$state" ]
}
ap_log_process() {
    local pid=$1 f0 f1 f2
    ap_valid_pid "$pid" || return 1
    [ "$pid" != "$$" ] || return 1
    f0=$(readlink "/proc/$pid/fd/0" 2>/dev/null)
    f1=$(readlink "/proc/$pid/fd/1" 2>/dev/null)
    f2=$(readlink "/proc/$pid/fd/2" 2>/dev/null)
    case "$f0 $f1 $f2" in *"$TMP_DIR/hostapd.log"*|*"$TMP_DIR/dnsmasq.log"*|*"$TMP_DIR/dnsmasq.fifo"*) return 0;; esac
    return 1
}
ap_stop_children() {
    local p pid remaining rounds=0
    # Also handles descendants left by the prior package version; never killall.
    for p in /proc/[0-9]*; do
        pid=${p##*/}
        ap_log_process "$pid" && kill "$pid" 2>/dev/null
    done
    while [ "$rounds" -lt 5 ]; do
        remaining=0
        for p in /proc/[0-9]*; do
            pid=${p##*/}
            if ap_log_process "$pid"; then
                remaining=1
                [ "$rounds" -ge 3 ] && kill -9 "$pid" 2>/dev/null
            fi
        done
        [ "$remaining" = 0 ] && break
        sleep 1; rounds=$((rounds+1))
    done
    [ "$remaining" = 0 ] || return 1
    # Reap our wrapper/writer after its actual daemon is gone. Do not wait on
    # unrelated or still-running PIDs; child checks include PPid and zombie state.
    for pid in "${HOSTAPD_WRAPPER:-}" "${DNSMASQ_WRAPPER:-}"; do
        ap_valid_pid "$pid" || continue
        ap_child_live "$pid" && kill "$pid" 2>/dev/null
        if ! ap_child_live "$pid"; then wait "$pid" 2>/dev/null || :; fi
    done
    return 0
}
ap_start_logged() {
    local kind=$1; shift
    case "$kind" in hostapd|dnsmasq) ;; *) return 1;; esac
    rm -f "$TMP_DIR/$kind.pid" "$TMP_DIR/$kind.exit"
    : > "$TMP_DIR/$kind.log" || return 1
    (
        if [ "$kind" = dnsmasq ]; then
            # Android dnsmasq listens for netd's update_ifaces command on stdin.
            # This car has neither mknod nor mkfifo. Use a real anonymous pipe,
            # never a regular-file fallback lacking a persistent control channel.
            ap_dns_writer 2>> "$TMP_DIR/dnsmasq.log" | "$@" >> "$TMP_DIR/$kind.log" 2>&1 &
        else
            "$@" >> "$TMP_DIR/$kind.log" 2>&1 &
        fi
        child=$!
        echo "$child" > "$TMP_DIR/$kind.pid"
        wait "$child"
        code=$?
        echo "$code" > "$TMP_DIR/$kind.exit"
        echo "AP_CHILD_EXIT kind=$kind code=$code"
    ) &
    case "$kind" in hostapd) HOSTAPD_WRAPPER=$!;; dnsmasq) DNSMASQ_WRAPPER=$!;; esac
}
ap_dns_writer() {
    local count=0
    echo -n "update_ifaces:$ACTIVE_IFACE" || return 1
    # $! for the background pipeline is dnsmasq, its last process. Its PID can
    # be published before that child redirects fd1. Allow both publication and
    # the owned log descriptor to become visible before the lifecycle loop.
    while ! ap_running dnsmasq; do
        [ -f "$TMP_DIR/dnsmasq.exit" ] && return 0
        [ "$count" -ge 3 ] && return 0
        sleep 1; count=$((count+1))
    done
    while ap_running dnsmasq; do sleep 1; done
}
ap_running() {
    local pid fd
    [ -f "$TMP_DIR/$1.pid" ] || return 1
    read -r pid < "$TMP_DIR/$1.pid" || return 1
    ap_valid_pid "$pid" || return 1
    fd=$(readlink "/proc/$pid/fd/1" 2>/dev/null)
    case "$fd" in "$TMP_DIR/$1.log"*) return 0;; esac
    return 1
}
