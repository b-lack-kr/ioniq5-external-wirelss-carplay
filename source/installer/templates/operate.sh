# Sourced from the verified local kit. Never execute an install payload straight from removable media.
UUID=bd5f651c-38f9-8ef4-827c-34ad706c2b5c
PUBLIC_MANIFEST=@PUBLIC_MOD_MD5@
B=$R/backup
MAP_PARENT=$H/maps/hyundai/neev/kor
MAP=$MAP_PARENT/260902
CHECK=$H/state/map-check.json
VENDOR=$W/vendor/hkg-2.0.2
mode=$(cat "$R/mode")
case "$mode" in fresh) old_md5=absent;; 0.1.10) old_md5=b82a23b7e928c80d4c90fa16e8767eb8;; 0.1.11) old_md5=c57459b715e3e5eeb6e6fc5c8bdd35e1;; *) stop BAD_MODE;; esac
mark() { echo "$1" > "$R/STATUS.next" && sync && mv "$R/STATUS.next" "$R/STATUS" && sync || stop STATUS_WRITE; }
app() { ( export CLASSPATH="$1" ANDROID_ROOT=/system ANDROID_DATA=/data; shift; exec /system/bin/app_process /system/bin "$@" ); }
engine() { app /system/framework/hmgapi-hook.jar hmgapi.engine.Engine "$@"; }
helper() { app "$U/payload/setup.jar" kr.ioniq5.usbcp.setup.Setup "$@"; }
signed() { app /system/framework/hmgapi-hook.jar hmgapi.engine.Signing verify "$1" "$UUID"; }
# Signing.verify rejects engine-generated .features. Verify all signed members in a
# private snapshot after removing only the engine markers, never edits the installed tree.
copy_signed() {
    dir "$1" && absent "$2" && regular "$1/META-HMGAPI/manifest.json" &&
        [ "$(hash "$1/META-HMGAPI/manifest.json")" = "$3" ] || return 1
    cp -R "$1" "$2" || return 1
    for g in .features .disabled; do
        [ ! -L "$2/$g" ] || return 1
        if ! absent "$2/$g"; then rm -rf "$2/$g" || return 1; fi
    done
    signed "$2"
}
known_mod() {
    dir "$MOD" && regular "$MOD/META-HMGAPI/manifest.json" && [ ! -L "$MOD/META-HMGAPI" ] &&
        [ "$(hash "$MOD/META-HMGAPI/manifest.json")" = "$1" ]
}
verify_public() {
    # Verify the installed files as well as their signer, allowing only HMG's generated markers.
    snapshot="$R/verify-$boot-$$"
    copy_signed "$MOD" "$snapshot" "$PUBLIC_MANIFEST" || return 1
    rm -rf "$snapshot"
}
# HMG apply is global. Only our two feature file paths and three exact properties may change.
check_plan() {
    how=$1; log=$2
    engine plan > "$log" 2>&1 || return 1
    while read action key a b rest; do
        if [ "$action" = setting ]; then
            if [ "$key" = prop/persist.ium.support ]; then value=carplay
            elif [ "$key" = prop/persist.sys.iap2.wifi ] || [ "$key" = prop/persist.iap2.iden.wireless ]; then value=t
            else return 1; fi
            [ "$how:$a:$b:$rest" = "apply:=:$value:[$ID]" ] || [ "$how:$a:$b:$rest" = "remove:back:to:stock" ] || return 1
        elif [ "$action" = change ] || [ "$action" = update ] || [ "$action" = restore ]; then
            [ "$key" = /system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml ] ||
                [ "$key" = /system/onebin/neev_kr/featurexml.cfg ] || return 1
            [ "$how:$action:$a" = "apply:change:[$ID]" ] || [ "$how:$action:$a" = "apply:update:[$ID]" ] ||
                [ "$how:$action:$a" = "remove:restore:(stock)" ] || return 1
        elif [ "$action" = bind ] || [ "$action" = unbind ] || [ "$action" = problem ]; then return 1; fi
    done < "$log"
}
map_ok() { engine map > "$R/map.log" && grep -F -q 'hooks on' "$R/map.log"; }
active_plan_ok() {
    grep -F -q "$ID won't load" "$1" && return 1
    grep -F -q 'hooks are off' "$1" && return 1
    return 0
}
SERVICE_GROUP_FILE="$R/service-groups-$boot.txt"
record_group() {
    pid=$1
    case "$pid" in ''|0) return 0;; *[!0-9]*) return 1;; esac
    [ "$pid" -gt 1 ] || return 1
    # hmgapid starts each supervised service in its own process group (setsid).
    if [ -r "/proc/$pid/stat" ]; then
        IFS= read -r stat < "/proc/$pid/stat" || return 1
        case "$stat" in *') '*) stat=${stat##*) };; *) return 1;; esac
        set -- $stat
        [ "$#" -ge 3 ] && [ "$3" = "$pid" ] || return 1
    fi
    grep -qx "$pid" "$SERVICE_GROUP_FILE" 2>/dev/null || echo "$pid" >> "$SERVICE_GROUP_FILE"
}
sample_service() {
    wanted=$1; found=0; service_state=""; service_pid=""
    [ -f "$H/state/services.status" ] || return 1
    while read id name state pid rest; do
        [ "$id" = "$ID" ] && [ "$name" = "$wanted" ] || continue
        found=1; service_state=$state; service_pid=$pid
        record_group "$pid" || return 1
    done < "$H/state/services.status"
    [ "$found" = 1 ]
}
groups_gone() {
    [ -f "$SERVICE_GROUP_FILE" ] || return 1
    while read group; do
        case "$group" in ''|*[!0-9]*) return 1;; esac
        # Capture descendants too: hmgapid sends TERM to each service's whole group.
        for p in /proc/[0-9]*; do
            [ -r "$p/stat" ] || continue
            IFS= read -r stat < "$p/stat" || continue
            case "$stat" in *') '*) stat=${stat##*) };; *) return 1;; esac
            set -- $stat
            [ "$#" -ge 3 ] || return 1
            [ "$3" != "$group" ] || return 1
        done
    done < "$SERVICE_GROUP_FILE"
    return 0
}
wait_groups() {
    n=0
    while [ "$n" -lt 25 ]; do
        if groups_gone; then : > "$SERVICE_GROUP_FILE"; return $?; fi
        sleep 1; n=$((n+1))
    done
    return 1
}
stop_one() {
    wanted=$1
    sample_service "$wanted" || return 1
    # Wait for each queued command before sending the next; hmgapid may use one ctl file.
    /system/bin/hmgapid service-stop "$ID" "$wanted" > "$R/stop-$wanted.log" 2>&1 || return 1
    n=0
    while [ "$n" -lt 25 ]; do
        sample_service "$wanted" || return 1
        [ "$service_state" = stopped ] && [ "$service_pid" = 0 ] && return 0
        sleep 1; n=$((n+1))
    done
    return 1
}
stop_services_and_disable() {
    [ -f "$SERVICE_GROUP_FILE" ] || : > "$SERVICE_GROUP_FILE" || return 1
    if [ ! -e "$MOD/.disabled" ]; then
        stop_one usb-ap || return 1
        stop_one ap-provider || return 1
        /system/bin/hmgapid disable "$ID" > "$R/disable.log" 2>&1 || return 1
    fi
    wait_groups
}
ensure_target() {
    [ -x /system/bin/hostapd ] && [ -x /system/bin/dnsmasq ] && regular /system/bin/app_process.hmgapi-stock || stop RUNTIME_MISSING
    regular "$H/hook/enable" && absent "$H/hook/disable" || stop ENABLE_HMG_HOOKS_FIRST
    while read digest path; do regular "$path" && [ "$(hash "$path")" = "$digest" ] || stop FIRMWARE_FILE_MISMATCH; done < "$U/payload/TARGET.md5"
    helper uid || stop PROJECTION_UID
}
preflight() {
    ensure_target
    absent "$H/state/journal.json" || stop UNFINISHED_HMG_CHANGE
    absent "$W/use-existing-ap" && absent /data/nemesis/apps/usbwifi.hotspot || stop EXISTING_HOTSPOT_CONFLICT
    if ! absent "$H/mods/wireless-carplay-aa"; then
        dir "$H/mods/wireless-carplay-aa" && regular "$H/mods/wireless-carplay-aa/.disabled" || stop EXISTING_WIRELESS_PROJECTION_CONFLICT
    fi
    signed "$U/payload/mod" && [ "$(hash "$U/payload/mod/META-HMGAPI/manifest.json")" = "$PUBLIC_MANIFEST" ] || stop PUBLIC_SIGNATURE
    /system/bin/sh "$U/import-hkg.sh" "$USB" check || stop E_VENDOR_ASSETS
    check_plan clean "$R/plan-before.log" || stop UNAPPLIED_OTHER_CHANGES
    if [ "$mode" = fresh ]; then
        absent "$MOD" && absent "$W/config" || stop FRESH_PATH_ALREADY_EXISTS
    else
        known_mod "$old_md5" && absent "$MOD/.disabled" && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = "$mode" ] &&
            dir "$W/config" && regular "$W/config/hotspot.conf" && regular "$W/config/ap-identity" || stop MIGRATION_BASE_CHANGED
        map_ok || stop PREVIOUS_MAP_NOT_ACTIVE
    fi
    for d in "$H/maps/hyundai" "$H/maps/hyundai/neev" "$MAP_PARENT" "$MAP" "$W/vendor" "$VENDOR"; do
        absent "$d" || dir "$d" || stop SYMLINK_PATH
    done
    absent "$CHECK" || regular "$CHECK" || stop UNSAFE_MAP_CHECK
}
prepare() {
    if ! absent "$B"; then
        # PREPARED is written prior to all live mutations. Preserve unfinished backups.
        mv "$B" "$R/unused-backup-$boot-$$" || stop BACKUP_BUSY
    fi
    mkdir "$B" || stop BACKUP_CREATE
    if dir "$VENDOR"; then : > "$B/vendor-existed"; fi
    if [ "$mode" != fresh ]; then
        copy_signed "$MOD" "$B/mod" "$old_md5" || stop EXISTING_MOD_SIGNATURE
    else
        if regular "$CHECK"; then cp -p "$CHECK" "$B/map-check" || stop MAP_STATE_BACKUP; fi
        mkdir -p "$B/proposal/hyundai/neev/kor" || stop MAP_STAGE
        if dir "$MAP"; then
            helper tree "$MAP" "$B/map-before.json" && cp -R "$MAP" "$B/proposal/hyundai/neev/kor/260902" &&
                helper same-tree "$B/proposal/hyundai/neev/kor/260902" "$B/map-before.json" || stop MAP_BACKUP
        fi
        helper merge "$B/proposal" "$U/payload/maps" && helper tree "$B/proposal/hyundai/neev/kor/260902" "$B/map-after.json" || stop MAP_CONFLICT
        mkdir "$B/config" && helper config "$B/config/hotspot.conf" && regular "$B/config/ap-identity" ||
            stop FRESH_CONFIG
        helper tree "$B/config" "$B/config-after.json" || stop FRESH_CONFIG
    fi
    mark READY
}
restore_map() {
    # Shared map data may now be used by a separate add-on (including Mute Pause).
    # Always keep changed maps; on an explicit uninstall keep it for other enabled consumers.
    if dir "$MAP"; then
        if regular "$B/map-before.json" && helper same-tree "$MAP" "$B/map-before.json"; then return 0; fi
        helper same-tree "$MAP" "$B/map-after.json" || { echo 'MAP_PRESERVED_OWNER_CHANGED'; return 0; }
        if regular "$B/previously-installed"; then
            for c in "$H/mods"/*; do
                if dir "$c" && regular "$c/mod.json" && absent "$c/.disabled"; then echo 'MAP_PRESERVED_FOR_OTHER_MOD'; return 0; fi
            done
        fi
        absent "$B/published-map" && mv "$MAP" "$B/published-map" || return 1
    fi
    if dir "$B/original-map"; then absent "$MAP" && mv "$B/original-map" "$MAP" || return 1; fi
    if regular "$B/own-map-check"; then
        if regular "$CHECK" && [ "$(hash "$CHECK")" != "$(cat "$B/own-map-check")" ]; then return 0; fi
    else
        # A crash between verification and recording its digest leaves ownership unproven.
        return 0
    fi
    if regular "$B/map-check"; then cp -p "$B/map-check" "$CHECK" || return 1
    else rm -f "$CHECK" || return 1; fi
}
rollback() {
    mark RESTORING
    if ! absent "$H/state/journal.json"; then regular "$B/apply-pending" && engine recover || return 1; fi
    if ! absent "$MOD"; then
        if known_mod "$PUBLIC_MANIFEST"; then
            # Only remove/replace the authentic installed build. Keep unowned directories untouched.
            verify_public || return 1
            stop_services_and_disable || return 1
            if [ "$mode" = fresh ]; then engine remove "$ID" --from-nemesis || return 1; fi
        elif [ "$mode" = fresh ] || ! known_mod "$old_md5"; then return 1; fi
    fi
    if [ "$mode" = fresh ]; then
        if regular "$B/mod-install-started"; then
            check_plan remove "$R/restore-plan.log" || return 1
            : > "$B/apply-pending"; sync; engine apply || return 1
            rm -f "$B/apply-pending" || return 1
        fi
        restore_map || return 1
        if dir "$W/config" && regular "$B/config-after.json" && helper same-tree "$W/config" "$B/config-after.json"; then
            absent "$B/removed-config" && mv "$W/config" "$B/removed-config" || return 1
        fi
        echo none > "$W/UPGRADE_CURRENT.txt" || return 1
    else
        known_mod "$old_md5" || { signed "$B/mod" && engine install "$B/mod" --uuid "$UUID" --force --from-nemesis; } || return 1
        /system/bin/hmgapid enable "$ID" && check_plan clean "$R/restore-plan.log" || return 1
        echo "$mode" > "$W/UPGRADE_CURRENT.txt" || return 1
    fi
    if ! regular "$B/vendor-existed" && regular "$B/vendor-after.json" && dir "$VENDOR" &&
        helper same-tree "$VENDOR" "$B/vendor-after.json"; then
        absent "$B/removed-vendor" && mv "$VENDOR" "$B/removed-vendor" || return 1
    fi
    rm -f "$R/MUTE_1_0_0_DONE" || return 1
    mark RESTORED
    notice 'CarPlay 공개판 적용 전 상태로 복구했습니다. 내비를 재부팅하세요.'
}
install() {
    mark INSTALLING
    /system/bin/sh "$U/import-hkg.sh" "$USB" install || return 1
    helper tree "$VENDOR" "$B/vendor-after.json" || return 1
    if [ "$mode" = fresh ]; then
        absent "$W/config" && mv "$B/config" "$W/config" || return 1
        app "$U/payload/mod/provider.jar" kr.ioniq5.usbcp.ApIdentity preflight "$W/config/hotspot.conf" "$W/config/ap-identity" || return 1
        for d in "$H/maps/hyundai" "$H/maps/hyundai/neev" "$MAP_PARENT"; do
            if absent "$d"; then mkdir "$d" && chmod 755 "$d" || return 1; else dir "$d" || return 1; fi
        done
        if regular "$B/map-before.json"; then
            helper same-tree "$MAP" "$B/map-before.json" && absent "$B/original-map" && mv "$MAP" "$B/original-map" || return 1
        fi
        absent "$MAP" && mv "$B/proposal/hyundai/neev/kor/260902" "$MAP" || return 1
        # Apps need to read the map through the HMG loader. It contains public descriptors only.
        chmod 755 "$MAP" || return 1
        for f in "$MAP"/*.json; do regular "$f" && chmod 644 "$f" || return 1; done
        verify_rc=0
        engine verify-map > "$R/verify-map.log" 2>&1 || verify_rc=$?
        if regular "$CHECK"; then hash "$CHECK" > "$B/own-map-check" || return 1; fi
        [ "$verify_rc" = 0 ] && regular "$B/own-map-check" &&
            grep -F -q 'map hyundai/neev/kor/260902:' "$R/verify-map.log" && map_ok || return 1
    else
        stop_services_and_disable || return 1
    fi
    : > "$B/mod-install-started"; sync
    engine install "$U/payload/mod" --uuid "$UUID" --from-nemesis > "$R/mod-install.log" 2>&1 &&
        /system/bin/hmgapid enable "$ID" || return 1
    if [ "$mode" = fresh ]; then
        check_plan apply "$R/plan.log" && active_plan_ok "$R/plan.log" || return 1
        : > "$B/apply-pending"; sync; engine apply > "$R/apply.log" 2>&1 || return 1
        rm -f "$B/apply-pending" || return 1
    fi
    check_plan clean "$R/final-plan.log" && active_plan_ok "$R/final-plan.log" && known_mod "$PUBLIC_MANIFEST" || return 1
    echo 1.0.0 > "$W/UPGRADE_CURRENT.txt" && : > "$B/previously-installed" || return 1
    mark INSTALLED
    if [ "${NEEV_CP_BUNDLE:-0}" = 1 ]; then notice 'CarPlay 설치 완료. USB를 꽂아 두세요.'
    else notice 'CarPlay 1.0.0 설치 완료. 동글 연결 후 내비를 재부팅하세요.'; fi
}
current=$(cat "$R/STATUS")
case "$current:$restore" in
    INSTALLED:0) verify_public && [ "$(cat "$W/UPGRADE_CURRENT.txt")" = 1.0.0 ] || stop INSTALLED_STATE_MISMATCH
        notice 'CarPlay 1.0.0 설치 상태를 확인했습니다'; exit 0;;
    INSTALLED:1)
        check_plan clean "$R/pre-restore-plan.log" || stop UNAPPLIED_OTHER_CHANGES
        rollback || stop RESTORE_REQUIRED; exit 0;;
    INSTALLING:*|RESTORING:*) rollback || stop RESTORE_REQUIRED; exit 0;;
    RESTORED:1) exit 0;;
    PREPARED:1|READY:1)
        if [ "$mode" = fresh ]; then echo none > "$W/UPGRADE_CURRENT.txt" || stop STATUS_WRITE; fi
        rm -f "$R/MUTE_1_0_0_DONE" || stop STATUS_WRITE
        mark RESTORED; exit 0;;
    RESTORED:0) mark PREPARED;;
    PREPARED:0|READY:0) ;;
    *) stop UNKNOWN_TRANSACTION;;
esac
preflight
prepare
notice 'CarPlay 1.0.0 설치 준비 완료'
if ! install; then
    rollback || stop RESTORE_REQUIRED
    stop INSTALL_FAILED_RESTORED
fi
