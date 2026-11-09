# Read-only HMG hook diagnostics. Export only booleans and a validated counter.
# Never export process maps, command lines, other app identities, or raw file text.
hook_diagnostics() (
    hook_enable=0; hook_disable=0; hook_library=0; starts_exists=0; starts_valid=0; starts_value=
    zygote_found=0; zygote_maps_readable=0; zygote_hook_mapped=0
    [ ! -e /data/hmgapi/hook/enable ] || hook_enable=1
    [ ! -e /data/hmgapi/hook/disable ] || hook_disable=1
    [ ! -e /system/lib/libhmgapi_hook.so ] || hook_library=1
    [ ! -e /data/hmgapi/hook/starts ] || starts_exists=1
    if [ ! -L /data/hmgapi ] && [ ! -L /data/hmgapi/hook ] &&
            [ -f /data/hmgapi/hook/starts ] && [ ! -L /data/hmgapi/hook/starts ]; then
        # Verified target toolbox layout: permissions owner group size date time name.
        meta=$(ls -l /data/hmgapi/hook/starts) || meta=
        set -- $meta
        if [ "$#" -ge 7 ]; then
            size=$4
            case "$size" in [1-9]|10|11)
                if exec 8< /data/hmgapi/hook/starts; then
                    starts_value=; extra=
                    IFS= read -r starts_value <&8 || :
                    if IFS= read -r extra <&8 || [ -n "$extra" ]; then starts_value=; fi
                    exec 8<&-
                    case "$starts_value" in ''|*[!0-9]*|0?*) ;;
                        *)
                            if [ "${#starts_value}" -lt 10 ] ||
                                    { [ "${#starts_value}" = 10 ] && ! [ "$starts_value" \> 2147483647 ]; }; then
                                starts_valid=1
                            fi;;
                    esac
                fi;;
            esac
        fi
    fi
    # This 32-bit target has one init-owned, uid-0 zygote. A same-name app or
    # ambiguous match is not evidence about the main zygote's hook mapping.
    zygote=; matches=0; scanned=0
    for process in /proc/[0-9]*; do
        [ -d "$process" ] && [ ! -L "$process" ] || continue
        scanned=$((scanned+1)); [ "$scanned" -le 4096 ] || break
        name=; read -r name < "$process/comm" || continue
        [ "$name" = zygote ] || continue
        parent=; uid=; lines=0
        while read -r key first second third fourth rest; do
            lines=$((lines+1)); [ "$lines" -le 128 ] || break
            case "$key" in PPid:) parent=$first;; Uid:) [ "$first $second $third $fourth" != '0 0 0 0' ] || uid=0;; esac
        done < "$process/status"
        [ "$parent" = 1 ] && [ "$uid" = 0 ] || continue
        matches=$((matches+1)); zygote=$process
    done
    [ "$matches" = 0 ] || zygote_found=1
    if [ "$matches" = 1 ] && [ -r "$zygote/maps" ] && [ ! -L "$zygote/maps" ]; then
        zygote_maps_readable=1; lines=0
        while read -r region permission offset device inode path extra; do
            lines=$((lines+1)); [ "$lines" -le 16384 ] || { zygote_maps_readable=0; break; }
            if [ "$path" = /system/lib/libhmgapi_hook.so ]; then
                case "$extra" in ''|'(deleted)') zygote_hook_mapped=1;; esac
            fi
        done < "$zygote/maps"
        # Refuse a sample if the identified process stopped being zygote.
        name=; read -r name < "$zygote/comm" || name=
        [ "$name" = zygote ] || { zygote_maps_readable=0; zygote_hook_mapped=0; }
    fi
    echo "hook_enable_exists=$hook_enable"
    echo "hook_disable_exists=$hook_disable"
    echo "hook_library_exists=$hook_library"
    echo "hook_starts_exists=$starts_exists"
    echo "hook_starts_valid=$starts_valid"
    [ "$starts_valid" != 1 ] || echo "hook_starts=$starts_value"
    echo "zygote_found=$zygote_found"
    echo "zygote_maps_readable=$zygote_maps_readable"
    echo "zygote_hook_mapped=$zygote_hook_mapped"
)
