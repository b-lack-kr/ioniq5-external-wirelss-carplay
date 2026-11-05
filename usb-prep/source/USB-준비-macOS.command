#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-or-later
# Assemble the receiver's vehicle USB files locally. No elevated privileges are needed.
set -eu
export PATH=/usr/bin:/bin
unset UNZIP UNZIPOPT ZIPINFO ZIPINFOOPT
umask 077

stop() { printf '\n%s\n' "$*" >&2; exit 1; }
case "$#:${1:-}" in
    0:) mode=refresh ;;
    1:--prepare-only) mode=prepare ;;
    *) stop '사용법: USB-준비-macOS.command [--prepare-only]' ;;
esac
folder=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
kit_name=NEEV_260902_CarPlay_1.0.1_USB.zip
release_base='@CARPLAY_RELEASE_BASE@'
cache="$folder/HKG_5W_USBWifi_Hotspot.zip"
ready="$folder/USB_READY"
member=packages/usbwifi.hotspot.zip
url=https://github.com/GRT47/HKG_5W_USB_Wlan/releases/download/v2.0.2/HKG_5W_USBWifi_Hotspot.zip
kit_sha=17f565786fba31b93ad38d5d5a5f383b9f8fe7d1fab1b9368baa8a08de3167c9
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

printf '%s\n' '아이오닉 5 USB 무선 CarPlay 1.0.1 · by blackr'
work=$(/usr/bin/mktemp -d "$folder/.neev-usb.XXXXXXXX") || stop '압축을 푼 폴더에 쓸 수 없습니다. 다른 폴더로 옮겨 다시 실행하세요.'
trap '/bin/rm -rf "$work"' 0
trap 'exit 1' HUP INT TERM
stage="$work/media"
kit="$work/kit.zip"
if exists "$folder/$kit_name"; then
    [ -f "$folder/$kit_name" ] && [ ! -L "$folder/$kit_name" ] && cp "$folder/$kit_name" "$kit" || stop '옆에 있는 설치 ZIP을 읽지 못했습니다.'
else
    printf '%s\n' 'by blackr 공식 릴리스에서 CarPlay 설치 파일을 받는 중입니다…'
    download "$release_base/$kit_name" "$kit" || stop 'CarPlay 1.0.1 설치 파일을 받을 수 없습니다. 인터넷 연결을 확인하고 다시 실행하세요.'
fi
matches "$kit" "$kit_sha" || stop '설치 ZIP 검증에 실패했습니다. 1.0.1 공식 배포본을 확인하세요.'
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
    printf '%s\n' '기존 USB_READY가 1.0.1 준비 파일과 일치합니다.'
fi
printf '\n%s\n%s\n' "$ready" 'USB 메모리의 기존 파일을 지운 뒤 USB_READY 안의 항목 5개를 USB 최상위에 복사하세요.'
exit 0
fi
# Refresh one existing mounted volume. Partition tables, filesystem and label stay intact.
DISKUTIL=/usr/sbin/diskutil
IOREG=/usr/sbin/ioreg
plist() { /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null; }
internal_location() {
    "$DISKUTIL" info -plist "$1" > "$work/location.plist" &&
        [ "$(plist "$work/location.plist" Internal)" = true ]
}
connection() {
    # Registry entry IDs are per attachment, including reconnects on the same port.
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
eligible_disk() {
    case "$1" in disk*) number=${1#disk};; *) return 1;; esac
    case "$number" in ''|*[!0-9]*) return 1;; esac
    whole_info="$work/whole.plist"
    "$DISKUTIL" info -plist "/dev/$1" > "$whole_info" &&
        [ "$(plist "$whole_info" DeviceIdentifier)" = "$1" ] &&
        [ "$(plist "$whole_info" DeviceNode)" = "/dev/$1" ] &&
        [ "$(plist "$whole_info" WholeDisk)" = true ] &&
        [ "$(plist "$whole_info" VirtualOrPhysical)" = Physical ] &&
        [ "$(plist "$whole_info" Internal)" = false ] &&
        [ "$(plist "$whole_info" RemovableMedia)" = true ] &&
        [ "$(plist "$whole_info" WritableMedia)" = true ] &&
        [ "$(plist "$whole_info" BusProtocol)" = USB ] || return 1
    whole_bytes=$(plist "$whole_info" TotalSize) || return 1
    case "$whole_bytes" in ''|0|*[!0-9]*) return 1;; esac
    whole_scheme=$(plist "$whole_info" Content) || return 1
    volume_stamp=$(connection "$1") || return 1
    [ -n "$volume_stamp" ]
}
volume_text() { printf '%s' "$1" | tr -d '[:cntrl:]'; }
eligible_volume() {
    volume_id=$1
    case "$volume_id" in disk*) suffix=${volume_id#disk};; *) return 1;; esac
    case "$suffix" in ''|*[!0-9s]*|s*|*s|*ss*) return 1;; esac
    volume_info="$work/volume.plist"
    "$DISKUTIL" info -plist "/dev/$volume_id" > "$volume_info" &&
        [ "$(plist "$volume_info" DeviceIdentifier)" = "$volume_id" ] &&
        [ "$(plist "$volume_info" DeviceNode)" = "/dev/$volume_id" ] &&
        [ "$(plist "$volume_info" Internal)" = false ] &&
        [ "$(plist "$volume_info" Mounted)" = true ] &&
        [ "$(plist "$volume_info" WritableVolume)" = true ] || return 1
    volume_fs=$(plist "$volume_info" FilesystemType) || return 1
    case "$volume_fs" in msdos|exfat) ;; *) return 1;; esac
    if [ "$(plist "$volume_info" WholeDisk)" = true ]; then
        volume_parent=$volume_id
    else
        volume_parent=$(plist "$volume_info" ParentWholeDisk) || return 1
    fi
    eligible_disk "$volume_parent" || return 1
    volume_bytes=$(plist "$volume_info" TotalSize) || return 1
    case "$volume_bytes" in ''|0|*[!0-9]*) return 1;; esac
    [ "$volume_bytes" -ge 16777216 ] || return 1
    volume_uuid=$(plist "$volume_info" VolumeUUID) || return 1
    volume_mount=$(plist "$volume_info" MountPoint) || return 1
    volume_label=$(plist "$volume_info" VolumeName) || return 1
    [ -n "$volume_uuid" ] && [ -n "$volume_label" ] || return 1
    case "$volume_mount" in /Volumes/?*) ;; *) return 1;; esac
    # Newlines/control bytes cannot enter the selection records or terminal prompts.
    [ "$(volume_text "$volume_mount")" = "$volume_mount" ] &&
        [ "$(volume_text "$volume_label")" = "$volume_label" ] &&
        [ "$(volume_text "$volume_uuid")" = "$volume_uuid" ] || return 1
    [ -d "$volume_mount" ] && [ ! -L "$volume_mount" ] &&
        [ "$(CDPATH= cd -P -- "$volume_mount" && pwd -P)" = "$volume_mount" ] &&
        [ "$(df -P "$volume_mount" | awk 'END{print $1}')" = "/dev/$volume_id" ] || return 1
    volume_root=$(stat -f '%d:%i' "$volume_mount") || return 1
}
record_volume() {
    eligible_volume "$1" || return 0
    count=$((count+1))
    printf '%s\n' "$volume_id" "$volume_parent" "$volume_stamp" "$volume_bytes" \
        "$volume_uuid" "$volume_mount" "$volume_fs" "$volume_label" \
        "$whole_bytes" "$whole_scheme" "$volume_root" > "$work/choice-$count"
    printf '  %s) %s | %s | %s | %s GB\n' "$count" "$volume_id" "$volume_label" \
        "$volume_fs" "$(awk -v b="$volume_bytes" 'BEGIN{printf "%.1f",b/1000000000}')"
}
[ -t 0 ] || stop 'USB 파일 교체는 Terminal에서 직접 볼륨을 선택해야 합니다.'
# Only an internally booted Mac and internally staged distribution are accepted.
source_dev=$(df -P "$work" | awk 'END {print $1}')
internal_location / && internal_location "$source_dev" ||
    stop '내장 시동 디스크로 부팅하고, 배포 ZIP을 Mac 내장 저장 공간에 푼 뒤 실행하세요.'
list="$work/external.plist"
"$DISKUTIL" list -plist external physical > "$list" || stop 'USB 목록을 확인할 수 없습니다.'
count=0
index=0
printf '\n%s\n' '기존 파일을 지우고 설치 파일을 넣을 USB 볼륨을 선택하세요.'
printf '%s\n' '파티션·파일시스템·볼륨 이름은 유지됩니다. 같은 USB의 다른 볼륨은 변경하지 않습니다.'
while disk=$(plist "$list" "AllDisksAndPartitions:$index:DeviceIdentifier"); do
    pos=$index; index=$((index+1))
    record_volume "$disk"
    partindex=0
    while part=$(plist "$list" "AllDisksAndPartitions:$pos:Partitions:$partindex:DeviceIdentifier"); do
        partindex=$((partindex+1))
        record_volume "$part"
    done
done
[ "$count" -gt 0 ] || stop '사용 가능한 FAT32/exFAT USB 볼륨이 없습니다. 기존 USB가 Finder에 마운트되어 있는지 확인하세요. 이 도구는 포맷하지 않습니다.'
printf '%s' '번호를 입력하세요 (Enter로 취소): '
IFS= read -r choice || exit 1
[ -n "$choice" ] || { printf '%s\n' '취소했습니다.'; exit 0; }
case "$choice" in 0*|*[!0-9]*|????*) stop '표시된 번호만 입력하세요.';; esac
[ -f "$work/choice-$choice" ] || stop '표시된 번호만 입력하세요.'
{
    IFS= read -r selected; IFS= read -r selected_parent; IFS= read -r selected_stamp
    IFS= read -r selected_bytes; IFS= read -r selected_uuid; IFS= read -r selected_mount
    IFS= read -r selected_fs; IFS= read -r selected_label; IFS= read -r selected_whole_bytes
    IFS= read -r selected_scheme; IFS= read -r selected_root
} < "$work/choice-$choice"
printf '\n선택한 %s (%s)의 기존 파일과 폴더를 삭제합니다.\n' "$selected_label" "$selected"
printf '%s\n' 'macOS 관리 폴더 .Spotlight-V100, .fseventsd, .Trashes는 유지합니다.'
printf '계속하려면 DELETE %s 를 그대로 입력하세요: ' "$selected"
IFS= read -r answer || exit 1
[ "$answer" = "DELETE $selected" ] || { printf '%s\n' '취소했습니다.'; exit 0; }
post_check() {
    eligible_volume "$selected" &&
        [ "$volume_parent" = "$selected_parent" ] && [ "$volume_stamp" = "$selected_stamp" ] &&
        [ "$volume_bytes" = "$selected_bytes" ] && [ "$volume_uuid" = "$selected_uuid" ] &&
        [ "$volume_mount" = "$selected_mount" ] && [ "$volume_fs" = "$selected_fs" ] &&
        [ "$volume_label" = "$selected_label" ] && [ "$whole_bytes" = "$selected_whole_bytes" ] &&
        [ "$whole_scheme" = "$selected_scheme" ] && [ "$volume_root" = "$selected_root" ]
}
post_check || stop '선택한 USB의 연결 상태나 식별 정보가 바뀌었습니다. 다시 실행하세요.'
cd -P "$selected_mount" || stop '선택한 USB에 접근할 수 없습니다.'
here_check() {
    post_check && [ "$(stat -f '%d:%i' .)" = "$selected_root" ] &&
        [ "$(df -P . | awk 'END{print $1}')" = "/dev/$selected" ]
}
here_check || stop 'USB가 분리되었거나 볼륨 경로가 변경되었습니다.'
printf '%s\n' 'USB의 기존 파일을 삭제하는 중입니다…'
# Pin every directory by an O_NOFOLLOW handle. Do not recurse via pathnames:
# a substituted symlink or mount must never lead deletion outside this volume.
# All ordinary and hidden contents are preflighted before removing the old trigger.
perl - "$selected_root" <<'PERL_CLEAN_USB' || stop 'USB 내용을 안전하게 삭제하지 못했습니다. 접근 권한이나 다른 프로그램의 사용 여부를 확인하세요. 설치 파일 복사를 시작하지 않았습니다.'
use strict;
use warnings;
use Fcntl qw(O_RDONLY O_NOFOLLOW S_ISDIR S_ISREG S_ISLNK);
my ($device, $inode) = split /:/, shift @ARGV;
defined($device) && defined($inode) or die "Missing root identity\n";
sysopen(my $root, '.', O_RDONLY | O_NOFOLLOW) or die "Cannot pin USB root: $!\n";
my @root = stat($root);
@root && $root[0] == $device && $root[1] == $inode && S_ISDIR($root[2])
    or die "USB root changed\n";
my %managed = map { $_ => 1 } qw(.Spotlight-V100 .fseventsd .Trashes);
sub check_directory {
    my ($handle) = @_;
    my @pinned = stat($handle);
    my @current = lstat('.');
    @pinned && @current && $pinned[0] == $device && $current[0] == $device &&
        $pinned[1] == $current[1] && S_ISDIR($current[2])
        or die "USB directory changed\n";
}
sub walk {
    my ($parent, $remove, $depth) = @_;
    check_directory($parent);
    opendir(my $listing, '.') or die "Cannot inspect USB directory: $!\n";
    my @names = sort grep { $_ ne '.' && $_ ne '..' } readdir($listing);
    closedir($listing) or die "Cannot close USB listing: $!\n";
    for my $name (@names) {
        check_directory($parent);
        my @item = lstat($name);
        @item or die "USB entry changed: $!\n";
        $item[0] == $device or die "Nested mounted volume is not allowed\n";
        if (S_ISDIR($item[2])) {
            # These exact root directories are maintained by macOS. Do not
            # bypass their permissions or inspect their contents.
            next if $depth == 0 && $managed{$name};
            sysopen(my $child, $name, O_RDONLY | O_NOFOLLOW)
                or die "Cannot pin USB directory: $!\n";
            my @opened = stat($child);
            @opened && $opened[0] == $device && $opened[1] == $item[1] && S_ISDIR($opened[2])
                or die "USB directory was replaced\n";
            chdir($child) or die "Cannot enter USB directory: $!\n";
            walk($child, $remove, $depth + 1);
            chdir($parent) or die "Cannot return to pinned USB directory: $!\n";
            close($child) or die "Cannot close USB directory: $!\n";
            my @again = lstat($name);
            @again && $again[0] == $item[0] && $again[1] == $item[1] && S_ISDIR($again[2])
                or die "USB directory changed before removal\n";
            rmdir($name) or die "Cannot remove USB directory: $!\n" if $remove;
        } elsif (S_ISREG($item[2]) || S_ISLNK($item[2])) {
            # unlink removes a symlink itself; its destination is never opened.
            unlink($name) or die "Cannot remove USB file: $!\n" if $remove;
        } else {
            die "Unsupported special file on USB\n";
        }
    }
}
walk($root, 0, 0);
# Invalidate the old NEMESIS trigger before any payload can be partly removed.
my @trigger = lstat('NEMESIS.data');
if (@trigger && !S_ISDIR($trigger[2])) {
    unlink('NEMESIS.data') or die "Cannot remove old install trigger: $!\n";
}
walk($root, 1, 0);
check_directory($root);
opendir(my $remaining, '.') or die "Cannot verify USB cleanup: $!\n";
for my $name (grep { $_ ne '.' && $_ ne '..' } readdir($remaining)) {
    my @entry = lstat($name);
    @entry && $managed{$name} && $entry[0] == $device && S_ISDIR($entry[2])
        or die "USB still contains files after cleanup\n";
}
closedir($remaining) or die "Cannot close final USB listing: $!\n";
close($root) or die "Cannot close pinned USB root: $!\n";
PERL_CLEAN_USB
here_check || stop 'USB 연결이 바뀌어 복사를 중단했습니다.'
# Stream approved members without Finder metadata; publish the trigger only last.
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
here_check || stop 'USB 연결이 바뀌어 설치 항목을 등록하지 않았습니다.'
marker=.neev-descriptor-$$
! exists "$marker" && ! exists NEMESIS.data || stop 'USB 설치 지시 파일이 이미 있습니다.'
cat "$stage/NEMESIS.data" > "$marker" && cmp -s "$stage/NEMESIS.data" "$marker" && mv "$marker" NEMESIS.data && sync &&
    cmp -s "$stage/NEMESIS.data" NEMESIS.data || stop 'USB 설치 지시 파일 검증에 실패했습니다.'
printf '\n%s\n' '완료. 기존 USB 형식과 파티션을 유지한 채 31개 설치 파일을 확인했습니다. Finder에서 USB를 추출한 뒤 차량에 꽂으세요.'
