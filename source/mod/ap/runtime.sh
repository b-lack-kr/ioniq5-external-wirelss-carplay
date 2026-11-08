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
ap_external_setting() {
    # The helper reads the owner's bounded JSON as data once per service start.
    # Apply changes/restart reloads it; callbacks cannot override a user OFF.
    EXTERNAL_DONGLE=INVALID
    local selected
    selected=$(CLASSPATH="$APP_DIR/../provider.jar" /system/bin/app_process /system/bin kr.ioniq5.usbcp.ExternalDongleSetting 2>/dev/null) || return
    case "$selected" in ON|OFF) EXTERNAL_DONGLE=$selected;; esac
}
ap_power_start() {
    ap_child_live "${POWER_PID:-}" && return 0
    if ap_valid_pid "${POWER_PID:-}"; then wait "$POWER_PID" 2>/dev/null || :; fi
    # Keep the same-boot OFF checkpoint across reader/service restarts.
    # The reader validates its boot ID and atomically replaces this record.
    # APKs load the OEM automotive library through their boot/shared libraries.
    # app_process has no APK manifest; make that dependency explicit only for
    # this reader, without changing the AP provider or the inherited boot path.
    CLASSPATH="$APP_DIR/../provider.jar:/system/framework/automotive.jar" /system/bin/app_process /system/bin kr.ioniq5.usbcp.HeadUnitPower &
    POWER_PID=$!
}
ap_power_stop() {
    local rounds=0
    ap_child_live "${POWER_PID:-}" && kill "$POWER_PID" 2>/dev/null
    while ap_child_live "${POWER_PID:-}" && [ "$rounds" -lt 3 ]; do
        sleep 1; rounds=$((rounds+1))
    done
    ap_child_live "${POWER_PID:-}" && kill -9 "$POWER_PID" 2>/dev/null
    if ap_valid_pid "${POWER_PID:-}"; then wait "$POWER_PID" 2>/dev/null || :; fi
    POWER_PID=
}
ap_ns_valid() {
    local digits=$1 seconds count=0 now rest
    case "$digits" in ''|*[!0-9]*|0?*) return 1;; esac
    [ "${#digits}" -le 18 ] || return 1
    read -r now rest < /proc/uptime || return 1
    now=${now%%.*}
    case "$now" in ''|*[!0-9]*) return 1;; esac
    [ "${#now}" -le 9 ] || return 1
    # Never feed elapsed nanoseconds to KitKat's potentially 32-bit shell.
    seconds=$digits
    while [ "$count" -lt 9 ]; do seconds=${seconds%?}; count=$((count+1)); done
    [ "${seconds:-0}" -le "$now" ]
}
ap_ns_after() {
    # Both inputs have already been validated as canonical decimal integers.
    [ "${#1}" -gt "${#2}" ] && return 0
    [ "${#1}" -eq "${#2}" ] && [ "$1" \> "$2" ]
}
ap_power_sample() {
    local stamp value raw record_boot decision reason extra now rest boot
    POWER_OBSERVED=UNKNOWN; POWER_OBSERVED_NS=0
    POWER_READER_DIAG=ABSENT; POWER_READER_REASON=UNKNOWN
    ap_child_live "${POWER_PID:-}" || ap_power_start
    [ -f "$TMP_DIR/power-state" ] && [ ! -L "$TMP_DIR/power-state" ] || return
    POWER_READER_DIAG=INVALID
    read -r stamp value raw record_boot decision reason extra < "$TMP_DIR/power-state" || return
    [ -z "$extra" ] || return
    read -r boot < /proc/sys/kernel/random/boot_id || return
    ap_boot_id_valid "$boot" && [ "$record_boot" = "$boot" ] || return
    ap_ns_valid "$decision" || return
    case "$stamp" in ''|*[!0-9]*|0?*) return;; esac
    case "$reason" in ''|*[!A-Z_]*) return;; esac
    case "$raw" in unknown) ;; ''|*[!0-9]*) return;; esac
    [ "${#stamp}" -le 9 ] || return
    read -r now rest < /proc/uptime || return
    now=${now%%.*}
    [ "$stamp" -le "$now" ] || return
    # A committed OFF is a boot-scoped latch, not an expiring heartbeat.
    # An ON observation needs a live, fresh reader and a stable decision time.
    if [ "$value" = OFF ]; then
        [ "$decision" != 0 ] || return
    elif [ "$value" = ON ]; then
        POWER_READER_DIAG=STALE
        ap_child_live "${POWER_PID:-}" && [ "$((now-stamp))" -le 6 ] || return
    else return
    fi
    POWER_OBSERVED=$value; POWER_OBSERVED_NS=$decision
    POWER_READER_DIAG=VALID; POWER_READER_REASON=$reason
}
ap_boot_id_valid() {
    local remaining=$1 field size
    for size in 8 4 4 4; do
        field=${remaining%%-*}
        [ "${#field}" = "$size" ] || return 1
        case "$field" in *[!0-9a-f]*) return 1;; esac
        case "$remaining" in *-*) remaining=${remaining#*-};; *) return 1;; esac
    done
    [ "${#remaining}" = 12 ] || return 1
    case "$remaining" in *[!0-9a-f]*) return 1;; esac
    return 0
}
ap_power_choose() {
    local state=$1 stamp=$2
    if ap_ns_after "$stamp" "${POWER_LATEST_NS:-0}" ||
            { [ "$stamp" = "${POWER_LATEST_NS:-0}" ] && [ "$state" = OFF ]; }; then
        POWER_LATEST_NS=$stamp
        POWER_DECISION_SOURCE=${3:-UNKNOWN}
        case "$state" in OFF) POWER_PAUSED=1;; ON) POWER_PAUSED=0;; esac
    fi
}
ap_power_trace_file() (
    # Open/close for every append. Renaming inherited stdout would leave the
    # reader writing to the old inode; these two files have no persistent FD.
    umask 077
    local line=$1 file="$TMP_DIR/power-decision.log" backup="$TMP_DIR/power-decision.log.1" size=0 old=0
    [ -d "$TMP_DIR" ] && [ ! -L "$TMP_DIR" ] || return 0
    [ "${#line}" -le 2048 ] || return 0
    case "$line" in *[!A-Z_a-z0-9=\ ]*) return 0;; esac
    for candidate in "$file" "$backup"; do
        [ ! -L "$candidate" ] || return 0
        if [ -e "$candidate" ]; then
            [ -f "$candidate" ] || return 0
            # Verified NEEV KitKat toolbox layout: permissions owner group
            # size date time name. This target has neither wc nor printf.
            old=$(ls -l "$candidate") || return 0
            set -- $old
            [ "$#" -ge 7 ] || return 0
            case "$1" in -*) ;; *) return 0;; esac
            old=$4
            case "$old" in ''|*[!0-9]*) return 0;; esac
            [ "${#old}" -le 9 ] || return 0
            if [ "$old" -gt 65536 ]; then rm -f "$candidate" || return 0; old=0; fi
            chmod 600 "$candidate" 2>/dev/null || [ ! -e "$candidate" ] || return 0
        else old=0
        fi
        [ "$candidate" != "$file" ] || size=$old
    done
    if [ "$((size+${#line}+1))" -gt 65536 ]; then
        rm -f "$backup" && mv "$file" "$backup" || return 0
    fi
    # This target shell has no printf. Prefix/character checks above exclude
    # echo flags, backslashes and line breaks, so echo is literal here.
    echo "$line" >> "$file" || return 0
    chmod 600 "$file" 2>/dev/null || :
    return 0
)
ap_power_trace() {
    # Stable decision times, fixed categories only. Polling an unchanged input
    # must not create a new event or flood the persistent supervisor log.
    local line="paused=${POWER_PAUSED:-0} source=${POWER_DECISION_SOURCE:-BASELINE} decision_ns=${POWER_LATEST_NS:-0} event=${POWER_EVENT_DIAG:-NONE} event_ns=${POWER_EVENT_DIAG_NS:-0} reader=${POWER_READER_DIAG:-SKIPPED} reader_state=${POWER_OBSERVED:-UNKNOWN} reader_ns=${POWER_OBSERVED_NS:-0} reader_reason=${POWER_READER_REASON:-UNKNOWN}"
    [ "${AP_POWER_DIAG_LAST:-}" = "$line" ] && return 0
    AP_POWER_DIAG_LAST=$line
    ap_power_trace_file "AP_POWER_DECISION $line" 2>/dev/null || :
}
ap_power_gate() {
    # Compare actual observation times across the DiPO callback and root OEM
    # reader. An old ON record must never mask a newer OFF. Repeated getters do
    # not get new decision timestamps; UNKNOWN never clears the last decision.
    local event_boot event_ns event_state extra boot
    POWER_EVENT_DIAG=NONE; POWER_EVENT_DIAG_NS=0
    POWER_READER_DIAG=SKIPPED; POWER_READER_REASON=UNKNOWN
    POWER_OBSERVED=UNKNOWN; POWER_OBSERVED_NS=0
    if [ -e "$TMP_DIR/power-event" ] || [ -L "$TMP_DIR/power-event" ]; then
        POWER_EVENT_SEEN=1
        if [ -f "$TMP_DIR/power-event" ] && [ ! -L "$TMP_DIR/power-event" ] &&
                read -r event_boot event_ns event_state extra < "$TMP_DIR/power-event" &&
                read -r boot < /proc/sys/kernel/random/boot_id && [ -z "$extra" ] &&
                ap_boot_id_valid "$event_boot" && ap_boot_id_valid "$boot"; then
            if [ "$event_boot" != "$boot" ]; then
                POWER_EVENT_SEEN=0
                POWER_EVENT_DIAG=OLD_BOOT
            else
                [ "$event_ns" != 0 ] && ap_ns_valid "$event_ns" || event_state=INVALID
                case "$event_state" in
                    OFF|ON) POWER_EVENT_DIAG=VALID_$event_state; POWER_EVENT_DIAG_NS=$event_ns
                        ap_power_choose "$event_state" "$event_ns" EVENT;;
                    *) POWER_EVENT_DIAG=INVALID; POWER_DECISION_SOURCE=INVALID_EVENT
                        POWER_PAUSED=1; ap_power_trace; return 0;;
                esac
            fi
        else POWER_EVENT_DIAG=INVALID; POWER_DECISION_SOURCE=INVALID_EVENT
            POWER_PAUSED=1; ap_power_trace; return 0
        fi
    elif [ "${POWER_EVENT_SEEN:-0}" = 1 ]; then POWER_EVENT_DIAG=REMOVED; POWER_DECISION_SOURCE=REMOVED_EVENT
        POWER_PAUSED=1; ap_power_trace; return 0
    fi
    ap_power_sample
    case "$POWER_OBSERVED" in ON|OFF) ap_power_choose "$POWER_OBSERVED" "$POWER_OBSERVED_NS" READER;; esac
    ap_power_trace
    [ "${POWER_PAUSED:-0}" = 1 ]
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
