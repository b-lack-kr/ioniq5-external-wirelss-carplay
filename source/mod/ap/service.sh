#!/system/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Own one verified 5 GHz Realtek USB interface on the Korean NEEV head unit.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
umask 077

BASE=/data/nemesis/neev-usbcp
VENDOR=$BASE/vendor/hkg-2.0.2
APP_DIR=/data/hmgapi/mods/neev-usb-carplay/ap
TMP_DIR=/data/local/tmp/neev_usbcp
WORK=/data/misc/wifi/neev_usbcp
STATIONS=/data/local/tmp/usb_sta.list
CONF=$BASE/config/hotspot.conf
GW=192.168.43.1
NET=192.168.43.0/24
CHAIN=NEEVCP_USB_AP
ACTIVE_IFACE=
ACTIVE_TOKEN=
ACTIVE_IFINDEX=
ADDED_ROUTES=
ADDED_ADDRESS=0
. "$APP_DIR/runtime.sh" || exit 1

prepare_paths() {
    local item
    for item in "$BASE" "$VENDOR" "$VENDOR/bin" "$VENDOR/modules" "$BASE/config" "$CONF" "$TMP_DIR" "$WORK" "$WORK/ctrl" "$TMP_DIR/leases" "$STATIONS"; do
        [ ! -L "$item" ] || return 1
    done
    mkdir -p "$TMP_DIR" "$TMP_DIR/leases" "$WORK" "$WORK/ctrl" || return 1
    chown 0:0 "$TMP_DIR" && chmod 751 "$TMP_DIR" || return 1
    for item in "$TMP_DIR"/* "$WORK/entropy.bin" "$WORK/hostapd.conf"; do
        [ ! -L "$item" ] || return 1
    done
    for item in "$WORK" "$WORK/ctrl" "$TMP_DIR/leases"; do
        chown 1010:1010 "$item" && chmod 700 "$item" || return 1
    done
    touch "$STATIONS" && chown 1010:1010 "$STATIONS" && chmod 600 "$STATIONS"
}

matches_file() {
    local digest
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    digest=$(md5 "$1") && [ "${digest%% *}" = "$2" ]
}
check_assets() {
    matches_file "$VENDOR/bin/usbwifistatus.jar" f21b1f06c8b3655b6bea4ed7d19d9b59 &&
        matches_file "$VENDOR/modules/88x2bu.ko" dc2f643ceb8d5739d70960a54507fe8f &&
        matches_file "$VENDOR/modules/8851bu.ko" f127be464175f901a374554236c147d7
}

read_credentials() {
    local k v
    ssid=
    secret=
    [ -f "$CONF" ] && [ ! -L "$CONF" ] || return 1
    # Read installer-generated records as data, including any embedded '=' in the passphrase.
    while IFS= read -r v; do
        k=${v%%=*}; v=${v#*=}
        case "$k" in
            AP_SSID) [ -z "$ssid" ] || return 1; ssid=$v;;
            AP_PASS) [ -z "$secret" ] || return 1; secret=$v;;
        esac
    done < "$CONF"
    case "$ssid" in Hyundai_[0-9][0-9][0-9][0-9][0-9][0-9]) ;; *) return 1;; esac
    [ "${#secret}" -ge 8 ] && [ "${#secret}" -le 63 ]
}

adapter_family() {
    local d v p match seen=
    for d in /sys/bus/usb/devices/*; do
        [ -r "$d/idVendor" ] && [ -r "$d/idProduct" ] || continue
        IFS= read -r v < "$d/idVendor" || continue
        IFS= read -r p < "$d/idProduct" || continue
        case "$v:$p" in
            0bda:1a2b) match=storage;;
            *) match=$(grep -F "$v:$p " "$APP_DIR/driver-ids" 2>/dev/null) || continue
               match=${match#* }
               case "$match" in 88x2bu|8851bu) ;; *) continue;; esac;;
        esac
        if [ -n "$seen" ]; then echo ambiguous; return; fi
        seen=$match
    done
    echo "$seen"
}

load_driver() {
    local family=$1
    grep -q "^${family} " /proc/modules && return 0
    if [ "$family" = 88x2bu ]; then
        insmod "$VENDOR/modules/88x2bu.ko" ifname=rtwlan%d if2name=rtwlan%d rtw_power_mgnt=0 rtw_ips_mode=0 rtw_enusbss=0 rtw_en_napi=0 rtw_en_gro=0 >> "$TMP_DIR/insmod.log" 2>&1 && return 0
    fi
    insmod "$VENDOR/modules/$family.ko" ifname=rtwlan%d if2name=rtwlan%d >> "$TMP_DIR/insmod.log" 2>&1
}

switch_storage() {
    local d v p entry id
    for d in /sys/bus/usb/devices/*; do
        [ -r "$d/idVendor" ] && [ -r "$d/idProduct" ] || continue
        read -r v < "$d/idVendor" && read -r p < "$d/idProduct" || continue
        [ "$v:$p" = 0bda:1a2b ] || continue
        for entry in "$d"/*:*; do
            [ -d "$entry" ] || continue
            id=${entry##*/}
            [ -e "/sys/bus/usb/drivers/usb-storage/$id" ] || continue
            [ -w /sys/bus/usb/drivers/rtl8851bu/bind ] || return 1
            echo "$id" > /sys/bus/usb/drivers/usb-storage/unbind || return 1
            echo "$id" > /sys/bus/usb/drivers/rtl8851bu/bind || return 1
        done
    done
}

prepare_hostapd() {
    # Rebuild from this target's own binary on each service start. A prior daemon
    # may have left an executable in /data/local/tmp.
    [ "${HOSTAPD_READY:-0}" = 0 ] || return 0
    local temp="$TMP_DIR/hostapd_usb.new"
    rm -f "$temp" || return 1
    CLASSPATH="$VENDOR/bin/usbwifistatus.jar" /system/bin/app_process /system/bin com.hkg.usbwifi.UsbWifiStatusMain patch-hostapd /system/bin/hostapd "$temp" &&
        [ -s "$temp" ] && chmod 755 "$temp" && mv "$temp" "$TMP_DIR/hostapd_usb" || return 1
    HOSTAPD_READY=1
}

write_radio_config() {
    [ -s "$WORK/entropy.bin" ] || dd if=/dev/urandom of="$WORK/entropy.bin" bs=256 count=1 2>/dev/null || return 1
    cat > "$WORK/hostapd.conf" <<EOF
driver=nl80211
interface=$ACTIVE_IFACE
ctrl_interface=$WORK/ctrl
hw_mode=a
channel=36
ieee80211n=1
ssid=$ssid
wpa=2
rsn_pairwise=CCMP
wpa_passphrase=$secret
EOF
    [ "$?" = 0 ] && chown 1010:1010 "$WORK/entropy.bin" "$WORK/hostapd.conf" &&
        chmod 600 "$WORK/entropy.bin" "$WORK/hostapd.conf"
}

ensure_address() {
    local addresses
    addresses=$(ip -4 addr show dev "$ACTIVE_IFACE") || return 1
    case "$addresses" in
        *"inet $GW/24 "*) return 0;;
        *"inet "*) return 1;;
        *) ip addr add "$GW/24" broadcast 192.168.43.255 dev "$ACTIVE_IFACE" || return 1
           ADDED_ADDRESS=1;;
    esac
}

open_network() {
    local table route
    ensure_address || return 1
    for table in main 60 61; do
        route=$(ip route show table "$table" | grep -F "$NET " )
        case "$route" in
            *" dev $ACTIVE_IFACE "*|*" dev $ACTIVE_IFACE") ;;
            '') ip route add "$NET" dev "$ACTIVE_IFACE" src "$GW" table "$table" || return 1
                ADDED_ROUTES="$ADDED_ROUTES $table";;
            *) return 1;;
        esac
    done
    if ! iptables -nL "$CHAIN" >/dev/null 2>&1; then
        iptables -N "$CHAIN" || return 1
    fi
    iptables -D "$CHAIN" -j ACCEPT >/dev/null 2>&1
    iptables -A "$CHAIN" -j ACCEPT || return 1
    iptables -D INPUT -i "$ACTIVE_IFACE" -j "$CHAIN" >/dev/null 2>&1
    iptables -D OUTPUT -o "$ACTIVE_IFACE" -j "$CHAIN" >/dev/null 2>&1
    iptables -I INPUT 1 -i "$ACTIVE_IFACE" -j "$CHAIN" &&
        iptables -I OUTPUT 1 -o "$ACTIVE_IFACE" -j "$CHAIN"
}

stop_radio() {
    local table
    ap_stop_children || { ap_phase STOP_TIMEOUT; return 1; }
    if [ -n "$ACTIVE_IFACE" ]; then
        iptables -D INPUT -i "$ACTIVE_IFACE" -j "$CHAIN" >/dev/null 2>&1
        iptables -D OUTPUT -o "$ACTIVE_IFACE" -j "$CHAIN" >/dev/null 2>&1
        if ap_active_identity; then
            for table in $ADDED_ROUTES; do
                ip route del "$NET" dev "$ACTIVE_IFACE" table "$table" >/dev/null 2>&1
            done
            if [ "$ADDED_ADDRESS" = 1 ]; then
                ip addr del "$GW/24" dev "$ACTIVE_IFACE" >/dev/null 2>&1
            fi
        fi
    fi
    ACTIVE_IFACE=
    ACTIVE_TOKEN=
    ACTIVE_IFINDEX=
    ADDED_ADDRESS=0
    ADDED_ROUTES=
    return 0
}

healthy_radio() {
    local index
    [ -n "$ACTIVE_IFACE" ] && read -r index < "/sys/class/net/$ACTIVE_IFACE/ifindex" &&
        [ "$index" = "$ACTIVE_IFINDEX" ] && ap_running hostapd && ap_running dnsmasq || return 1
    # The kernel ifindex changes across replug. The ordinary healthy loop only
    # reads state. Revalidate the USB identity in full before restoring an address.
    ip -4 addr show dev "$ACTIVE_IFACE" | grep -Fq "inet $GW/24 " && return 0
    ap_active_identity && ensure_address
}

run_radio() {
    read_credentials || { ap_phase CONFIG_INVALID; return 1; }
    prepare_hostapd || { ap_phase HOSTAPD_PATCH_FAILED; return 1; }
    write_radio_config || { ap_phase AP_PERMISSION_FAILED; return 1; }
    ap_active_identity && ip link set "$ACTIVE_IFACE" up || { ap_phase NETWORK_SETUP_FAILED; return 1; }
    sleep 2
    ap_active_identity && ensure_address || { ap_phase NETWORK_SETUP_FAILED; return 1; }
    ap_phase HOSTAPD_STARTING
    ap_start_logged hostapd "$TMP_DIR/hostapd_usb" -e "$WORK/entropy.bin" "$WORK/hostapd.conf" || return 1
    sleep 2
    ap_running hostapd || { ap_phase HOSTAPD_EXITED; return 1; }
    ap_active_identity && open_network || { ap_phase NETWORK_SETUP_FAILED; return 1; }
    ap_phase DHCP_STARTING
    # This adapter firmware redirects DHCP to port 1067. Android's dnsmasq also
    # needs a persistent netd-style stdin pipe (provided by ap_start_logged).
    ap_start_logged dnsmasq /system/bin/dnsmasq --keep-in-foreground --no-poll --no-resolv --port=0 \
        --interface="$ACTIVE_IFACE" --dhcp-alternate-port=1067,68 --dhcp-broadcast --log-dhcp \
        --dhcp-range=192.168.43.10,192.168.43.100,12h --dhcp-option=3 --dhcp-option=6,192.168.43.1 \
        --user=wifi --group=wifi --pid-file="$TMP_DIR/dnsmasq.pid" --dhcp-leasefile="$TMP_DIR/leases/dnsmasq.leases" || return 1
    sleep 1
    ap_running dnsmasq || { ap_phase DNSMASQ_EXITED; return 1; }
    ap_running hostapd || { ap_phase HOSTAPD_EXITED; return 1; }
    ap_phase SERVICES_RUNNING
}

[ "$(getprop ro.build.product)" = NEEV.KOR.0000.V039.001.260902 ] &&
    [ "$(getprop ro.board.platform)" = tcc803x ] && [ "$(cat /proc/sys/kernel/osrelease)" = 3.18.24-tcc ] || exit 2
[ ! -e "$BASE/use-existing-ap" ] || exit 3
prepare_paths || exit 3
exec >> "$TMP_DIR/daemon_stdout.log" 2>&1
check_assets || { ap_phase VENDOR_ASSETS_INVALID; exit 3; }
echo $$ > "$TMP_DIR/daemon.pid" || exit 3
ap_phase STARTING
trap 'stop_radio; exit 0' HUP INT TERM
ap_stop_children || { ap_phase STOP_TIMEOUT; exit 3; }
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done
while :; do
    if healthy_radio; then
        sleep 3 & wait $!
        continue
    fi
    if [ -n "$ACTIVE_IFACE" ]; then
        stop_radio || { sleep 3; continue; }
    fi
    family=$(adapter_family)
    case "$family" in
        '') ap_phase WAIT_DONGLE;;
        ambiguous) ap_phase IDENTITY_REJECTED;;
        storage) load_driver 8851bu && switch_storage || ap_phase DRIVER_FAILED;;
        *)
            if ! load_driver "$family"; then
                ap_phase DRIVER_FAILED
            elif ap_select; then
                ACTIVE_IFACE=$SELECT_IFACE; ACTIVE_TOKEN=$SELECT_TOKEN; ACTIVE_IFINDEX=$SELECT_INDEX
                ap_phase DEVICE_VERIFIED
                if ! run_radio; then stop_radio; sleep 7; fi
            else
                ap_phase WAIT_INTERFACE
            fi;;
    esac
    sleep 3 & wait $!
done
