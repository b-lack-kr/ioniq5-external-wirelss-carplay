#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Assemble the receiver's vehicle USB files locally. No elevated privileges are needed.
set -eu
export PATH=/usr/bin:/bin
unset UNZIP UNZIPOPT ZIPINFO ZIPINFOOPT
umask 077

stop() { printf '\n%s\n' "$*" >&2; exit 1; }
case "$#:${1:-}" in
    0:) mode=format ;;
    1:--prepare-only) mode=prepare ;;
    *) stop '사용법: USB-준비-macOS.command [--prepare-only]' ;;
esac
folder=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
kit_name=NEEV_260902_CarPlay_1.0.0_USB.zip
release_base='@CARPLAY_RELEASE_BASE@'
cache="$folder/HKG_5W_USBWifi_Hotspot.zip"
ready="$folder/USB_READY"
member=packages/usbwifi.hotspot.zip
url=https://github.com/GRT47/HKG_5W_USB_Wlan/releases/download/v2.0.2/HKG_5W_USBWifi_Hotspot.zip
kit_sha=dbd330c1a68e58efb4030050e4948121deeba69f99d453398f4e6fe687a8caaa
upstream_sha=7a7e5c7a46fbf160d7b9b91fe4333ea55e4865233450c285361f9ef1a11059d7
member_sha=d9077ba275398c1578444fdea479a5675e21f1b36f9ad3217e57b65aeadc512b

matches() {
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    actual=$(/usr/bin/shasum -a 256 < "$1") || return 1
    [ "${actual%% *}" = "$2" ]
}
exists() { [ -e "$1" ] || [ -L "$1" ]; }
conflict() { stop '기존 USB_READY의 내용이 다릅니다. 그 폴더를 다른 이름으로 보관한 뒤 다시 실행하세요.'; }
download() {
    curl --disable --fail --location --silent --show-error --retry 2 \
        --connect-timeout 15 --max-time 120 --proto '=https' --proto-redir '=https' \
        --output "$2" "$1"
}

printf '%s\n' '아이오닉 5 USB 무선 CarPlay 1.0.0 · by blackr'
work=$(/usr/bin/mktemp -d "$folder/.neev-usb.XXXXXXXX") || stop '압축을 푼 폴더에 쓸 수 없습니다. 다른 폴더로 옮겨 다시 실행하세요.'
trap '/bin/rm -rf "$work"' 0
trap 'exit 1' HUP INT TERM
stage="$work/media"
kit="$work/kit.zip"
if exists "$folder/$kit_name"; then
    [ -f "$folder/$kit_name" ] && [ ! -L "$folder/$kit_name" ] && cp "$folder/$kit_name" "$kit" || stop '옆에 있는 설치 ZIP을 읽지 못했습니다.'
else
    printf '%s\n' 'by blackr 공식 릴리스에서 CarPlay 설치 파일을 받는 중입니다…'
    download "$release_base/$kit_name" "$kit" || stop 'CarPlay 1.0.0 설치 파일을 받을 수 없습니다. 인터넷 연결을 확인하고 다시 실행하세요.'
fi
matches "$kit" "$kit_sha" || stop '설치 ZIP 검증에 실패했습니다. 1.0.0 공식 배포본을 확인하세요.'
mkdir "$stage"
unzip -q "$kit" -d "$stage" || stop '설치 ZIP을 풀지 못했습니다.'

if [ "$mode" = prepare ] && exists "$ready"; then
    [ -d "$ready" ] && [ ! -L "$ready" ] &&
        [ ! -L "$ready/packages" ] && matches "$ready/$member" "$member_sha" || conflict
    # An unchanged output can be checked again while offline.
    cp "$ready/$member" "$stage/$member" || conflict
else
    upstream="$cache"
    if ! exists "$upstream"; then
        upstream="$work/upstream.zip"
        printf '%s\n' 'GRT47 공식 릴리스에서 Wi-Fi 파일을 받는 중입니다…'
        download "$url" "$upstream" || stop '공식 Wi-Fi 파일을 받을 수 없습니다. 인터넷 연결을 확인하고 다시 실행하세요.'
    fi
    matches "$upstream" "$upstream_sha" || stop 'GRT47 v2.0.2 원본 해시가 다릅니다. 같은 폴더의 기존 HKG ZIP이 있다면 확인하세요.'
    unzip -p "$upstream" "$member" > "$stage/$member" || stop '원본 ZIP에서 USB Wi-Fi 파일을 꺼내지 못했습니다.'
fi
matches "$stage/$member" "$member_sha" || stop 'USB Wi-Fi 파일의 무결성 검증에 실패했습니다.'

if [ "$mode" = prepare ]; then
# Serialize the short publish step. The OS releases this lock if interrupted.
# rename() targets this exact path (unlike mv, which may put a folder inside another).
if perl -MFcntl=:flock -e '
    open(my $guard, "<", $ARGV[0]) or exit 1;
    flock($guard, LOCK_EX) or exit 1;
    exit 2 if -e $ARGV[2] || -l $ARGV[2];
    rename($ARGV[1], $ARGV[2]) or exit 1;
' "$0" "$stage" "$ready"; then
    printf '%s\n' 'USB 준비 파일을 생성했습니다.'
else
    result=$?
    [ "$result" -eq 2 ] || stop 'USB_READY 폴더를 만들 수 없습니다. 폴더 권한과 여유 공간을 확인하세요.'
    [ -d "$ready" ] && [ ! -L "$ready" ] || conflict
    special=$(find "$ready" ! -type f ! -type d -print -quit) || conflict
    [ -z "$special" ] && diff -qr "$stage" "$ready" >/dev/null || conflict
    printf '%s\n' '기존 USB_READY가 1.0.0 준비 파일과 일치합니다.'
fi
printf '\n%s\n%s\n' "$ready" 'USB_READY를 열고 안에 있는 항목 5개를 빈 USB 메모리의 최상위에 복사하세요.'
exit 0
fi
# Only physical, writable, removable USB media can reach the erase command.
DISKUTIL=/usr/sbin/diskutil
IOREG=/usr/sbin/ioreg
plist() { /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null; }
internal_location() {
    "$DISKUTIL" info -plist "$1" > "$work/location.plist" &&
        [ "$(plist "$work/location.plist" Internal)" = true ]
}
connection() {
    # Registry entry IDs are per attachment. A new device on the same connector
    # must not inherit the previous disk's authorization, even with the same name.
    "$IOREG" -r -c IOUSBHostDevice -l -w 0 > "$work/usb-registry" || return 1
    awk -v disk="$1" '
        /<class / && match($0, /id 0x[0-9a-fA-F]+,/) {
            current=substr($0,RSTART+3,RLENGTH-4)
            depth=index($0,"+-o")
            if (!depth) next
            for (d in usb) if (d+0>=depth) delete usb[d]
            if ($0 ~ /<class IOUSBHostDevice,/) usb[depth]=current
            media=$0 ~ /<class IOMedia,/ ? current : ""
        }
        /"BSD Name" =/ && $NF == "\"" disk "\"" && media != "" {
            level=0
            for (d in usb) if (d+0>level) level=d+0
            if (level) {
                found=usb[level] " " media
                if (seen!="" && found!=seen) ambiguous=1
                seen=found
            }
        }
        END { if (seen=="" || ambiguous) exit 1; print seen }
    ' "$work/usb-registry"
}
eligible() {
    case "$1" in disk*) number=${1#disk};; *) return 1;; esac
    case "$number" in ''|*[!0-9]*) return 1;; esac
    info="$work/whole.plist"
    "$DISKUTIL" info -plist "/dev/$1" > "$info" &&
        [ "$(plist "$info" DeviceIdentifier)" = "$1" ] &&
        [ "$(plist "$info" DeviceNode)" = "/dev/$1" ] &&
        [ "$(plist "$info" WholeDisk)" = true ] &&
        [ "$(plist "$info" VirtualOrPhysical)" = Physical ] &&
        [ "$(plist "$info" Internal)" = false ] &&
        [ "$(plist "$info" RemovableMedia)" = true ] &&
        [ "$(plist "$info" WritableMedia)" = true ] &&
        [ "$(plist "$info" BusProtocol)" = USB ] || return 1
    bytes=$(plist "$info" TotalSize) || return 1
    case "$bytes" in ''|0|*[!0-9]*) return 1;; esac
    [ "$bytes" -ge 16777216 ] || return 1
    stamp=$(connection "$1") || return 1
    [ -n "$stamp" ] || return 1
}
volume_text() { printf '%s' "$1" | tr -d '[:cntrl:]'; }
[ -t 0 ] || stop 'USB 포맷은 Terminal에서 직접 드라이브를 선택해야 합니다.'
# APFS logical stores complicate external-boot identification. Restrict use to an
# internal startup disk and an internally stored distribution, including all staging.
source_dev=$(df -P "$work" | awk 'END {print $1}')
internal_location / && internal_location "$source_dev" ||
    stop '내장 시동 디스크로 부팅하고, 배포 ZIP을 Mac 내장 저장 공간에 푼 뒤 실행하세요.'
list="$work/external.plist"
"$DISKUTIL" list -plist external physical > "$list" || stop '디스크 목록을 확인할 수 없습니다.'
count=0
index=0
printf '\n%s\n' '포맷할 이동식 USB 메모리를 선택하세요.'
while disk=$(plist "$list" "AllDisksAndPartitions:$index:DeviceIdentifier"); do
    pos=$index; index=$((index+1))
    if ! eligible "$disk"; then continue; fi
    count=$((count+1))
    printf '%s\n%s\n%s\n' "$disk" "$stamp" "$bytes" > "$work/choice-$count"
    model=$(plist "$info" MediaName) || model=$disk
    printf '  %s) %s | %s | %s GB' "$count" "$disk" "$(volume_text "$model")" "$(awk -v b="$bytes" 'BEGIN{printf "%.1f",b/1000000000}')"
    partindex=0
    while plist "$list" "AllDisksAndPartitions:$pos:Partitions:$partindex:DeviceIdentifier" >/dev/null; do
        label=$(plist "$list" "AllDisksAndPartitions:$pos:Partitions:$partindex:VolumeName") || label=''
        if [ -n "$label" ]; then printf ' | %s' "$(volume_text "$label")"; fi
        partindex=$((partindex+1))
    done
    printf '\n'
done
[ "$count" -gt 0 ] || stop '대상 USB를 찾지 못했습니다. 이동식 USB 메모리를 연결한 뒤 다시 실행하세요.'
printf '%s' '번호를 입력하세요 (Enter로 취소): '
IFS= read -r choice || exit 1
[ -n "$choice" ] || { printf '%s\n' '취소했습니다.'; exit 0; }
case "$choice" in 0*|*[!0-9]*|????*) stop '표시된 번호만 입력하세요.';; esac
[ -f "$work/choice-$choice" ] || stop '표시된 번호만 입력하세요.'
{ IFS= read -r selected; IFS= read -r selected_stamp; IFS= read -r selected_bytes; } < "$work/choice-$choice"
printf '\n선택한 %s 전체의 모든 파티션과 파일이 지워집니다.\n계속하려면 ERASE %s 를 그대로 입력하세요: ' "$selected" "$selected"
IFS= read -r answer || exit 1
[ "$answer" = "ERASE $selected" ] || { printf '%s\n' '취소했습니다.'; exit 0; }
eligible "$selected" && [ "$stamp" = "$selected_stamp" ] && [ "$bytes" = "$selected_bytes" ] ||
    stop '선택한 USB의 연결 상태나 식별 정보가 바뀌었습니다. 다시 실행하세요.'
if [ "$selected_bytes" -le 34359738368 ]; then format=FAT32; fstype=msdos
else format=ExFAT; fstype=exfat; fi
"$DISKUTIL" eraseDisk "$format" NEEV_USB MBRFormat "/dev/$selected" || stop '포맷하지 못했습니다. 디스크 유틸리티에서 장치 상태를 확인하세요.'
post_check() {
    eligible "$selected" && [ "${stamp%% *}" = "${selected_stamp%% *}" ] &&
        [ "$bytes" = "$selected_bytes" ] && [ "$(plist "$info" Content)" = FDisk_partition_scheme ] || return 1
    partinfo="$work/part.plist"
    "$DISKUTIL" info -plist "/dev/${selected}s1" > "$partinfo" &&
        [ "$(plist "$partinfo" ParentWholeDisk)" = "$selected" ] &&
        [ "$(plist "$partinfo" DeviceIdentifier)" = "${selected}s1" ] &&
        [ "$(plist "$partinfo" Internal)" = false ] &&
        [ "$(plist "$partinfo" FilesystemType)" = "$fstype" ] &&
        [ "$(plist "$partinfo" WritableVolume)" = true ] &&
        [ "$(plist "$partinfo" VolumeName)" = NEEV_USB ] || return 1
    mount=$(plist "$partinfo" MountPoint) || return 1
    case "$mount" in /Volumes/?*) ;; *) return 1;; esac
    [ -d "$mount" ] && [ ! -L "$mount" ]
}
tries=0
while ! post_check; do
    tries=$((tries+1))
    [ "$tries" -lt 5 ] || stop '새 USB 볼륨의 식별 정보를 확인하지 못해 복사를 중단했습니다.'
    sleep 1
done
cd -P "$mount" || stop '포맷된 USB에 접근할 수 없습니다.'
post_check && [ "$(df -P . | awk 'END{print $1}')" = "/dev/${selected}s1" ] || stop 'USB가 분리되었습니다.'
for root in NEEV_CP packages NEMESIS.sh NEEV_CP_INSTALL.sh NEMESIS.data; do
    if exists "$root"; then stop '새 볼륨에 다른 설치 파일이 있어 중단했습니다.'; fi
done
# Stream only the approved members instead of Finder copy (no quarantine/AppleDouble
# metadata in the signed tree). Leave NEMESIS.data unpublished until payload readback.
unzip -Z1 "$kit" > "$work/paths"
printf '%s\n' "$member" >> "$work/paths"
while IFS= read -r name; do
    [ "$name" != NEMESIS.data ] || continue
    case "$name" in */*) mkdir -p "${name%/*}";; esac
    (set -C; cat "$stage/$name" > "$name") || stop 'USB 파일 복사에 실패했습니다.'
done < "$work/paths"
sync
for root in NEEV_CP packages; do diff -qr "$stage/$root" "$root" >/dev/null || stop 'USB 파일 검증에 실패했습니다.'; done
for root in NEMESIS.sh NEEV_CP_INSTALL.sh; do cmp -s "$stage/$root" "$root" || stop 'USB 실행 파일 검증에 실패했습니다.'; done
post_check && [ "$(df -P . | awk 'END{print $1}')" = "/dev/${selected}s1" ] || stop 'USB 연결이 바뀌어 설치 항목을 등록하지 않았습니다.'
marker=.neev-descriptor-$$
! exists "$marker" && ! exists NEMESIS.data || stop 'USB 설치 지시 파일이 이미 있습니다.'
cat "$stage/NEMESIS.data" > "$marker" && cmp -s "$stage/NEMESIS.data" "$marker" && mv "$marker" NEMESIS.data && sync &&
    cmp -s "$stage/NEMESIS.data" NEMESIS.data || stop 'USB 설치 지시 파일 검증에 실패했습니다.'
printf '\n%s\n' '완료. 31개 설치 파일을 확인했습니다. Finder에서 USB를 추출한 뒤 차량에 꽂으세요.'
