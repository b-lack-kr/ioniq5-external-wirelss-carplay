#!/bin/sh
# Assemble the pinned release using macOS system tools; no Python required.
set -eu
umask 077
export PATH=/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin:/opt/homebrew/bin
folder=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
out= vendor=
while [ "$#" -gt 0 ]; do
 case "$1" in
  --output) [ "$#" -ge 2 ] || exit 2; out=$2; shift 2;;
  --vendor) [ "$#" -ge 2 ] || exit 2; vendor=$2; shift 2;;
  *) echo '잘못된 준비 인수입니다.' >&2; exit 2;;
 esac
done
[ -n "$out" ] && [ ! -e "$out" ] && [ ! -L "$out" ] || { echo '출력 경로를 사용할 수 없습니다.' >&2; exit 1; }
work=$(/usr/bin/mktemp -d "${out%/*}/.assemble.XXXXXXXX") || exit 1
trap '/bin/rm -rf "$work"' 0
trap 'exit 1' HUP INT TERM
fail() { printf '%s\n' "$*" >&2; exit 1; }
fetch() { /usr/bin/curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' --max-time 180 --max-filesize 67108864 -o "$2" "$1" || fail '필요한 파일을 내려받지 못했습니다.'; }
check_sha() { [ "$(/usr/bin/shasum -a 256 "$1" | /usr/bin/awk '{print $1}')" = "$2" ] || fail '다운로드 파일의 SHA-256 확인에 실패했습니다.'; }
TEMPLATE_SHA=bc5ec7d55f09d68be77635075cc851e21351bfad63d270ce8c5b41f8311e59c4
VENDOR_SHA=7a7e5c7a46fbf160d7b9b91fe4333ea55e4865233450c285361f9ef1a11059d7
WIFI_SHA=d9077ba275398c1578444fdea479a5675e21f1b36f9ad3217e57b65aeadc512b
# Release integration replaces this fixed URL with the pinned GitHub release asset.
TEMPLATE_URL='https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.1.3/bundle-template.zip'
template="$folder/bundle-template.zip"
if [ ! -f "$template" ] || [ -L "$template" ]; then
 case "$TEMPLATE_URL" in https://github.com/*) ;; *) fail '설치 템플릿 주소가 설정되지 않았습니다.';; esac
 template="$work/bundle-template.zip"; fetch "$TEMPLATE_URL" "$template"
fi
check_sha "$template" "$TEMPLATE_SHA"
if [ -z "$vendor" ]; then
 vendor="$work/HKG_5W_USBWifi_Hotspot.zip"
 fetch 'https://github.com/GRT47/HKG_5W_USB_Wlan/releases/download/v2.0.2/HKG_5W_USBWifi_Hotspot.zip?download=1' "$vendor"
fi
[ -f "$vendor" ] && [ ! -L "$vendor" ] || fail '동글 파일을 읽을 수 없습니다.'
check_sha "$vendor" "$VENDOR_SHA"
/usr/bin/unzip -tqq "$template" >/dev/null || fail '설치 템플릿이 손상되었습니다.'
/usr/bin/unzip -Z1 "$template" > "$work/template-list"
# The immutable hash additionally pins all path names and ZIP member types.
/usr/bin/awk 'BEGIN {ok=1} /^\// || /\\/ || /(^|\/)\.\.(\/|$)/ {ok=0} END {exit !ok}' "$work/template-list" || fail '설치 템플릿 경로가 안전하지 않습니다.'
mkdir "$work/USB_READY"
/usr/bin/unzip -qq "$template" -d "$work/USB_READY" || fail '설치 템플릿을 풀지 못했습니다.'
/usr/bin/unzip -p "$vendor" packages/usbwifi.hotspot.zip > "$work/usbwifi.hotspot.zip" || fail '동글 파일을 추출하지 못했습니다.'
check_sha "$work/usbwifi.hotspot.zip" "$WIFI_SHA"
mkdir -p "$work/USB_READY/CP/packages"
cp "$work/usbwifi.hotspot.zip" "$work/USB_READY/CP/packages/usbwifi.hotspot.zip"
# adb key is per user. Never distribute an ADB private key or a shared key.
keydir="$HOME/.android"; key="$keydir/adbkey.pub"; private="$keydir/adbkey"
if [ ! -e "$key" ] && [ ! -L "$key" ]; then
 [ ! -e "$private" ] && [ ! -L "$private" ] || fail 'ADB 개인키는 있으나 공개키가 없습니다. 해당 공개키를 복구해 주세요.'
 adb=$(command -v adb || :)
 if [ -z "$adb" ]; then
  printf '%s\n' 'Android 공식 platform-tools를 내려받아 이 컴퓨터의 ADB 키를 생성합니다.'
  fetch 'https://dl.google.com/android/repository/platform-tools_r35.0.2-darwin.zip' "$work/platform-tools.zip"
  check_sha "$work/platform-tools.zip" 1820078db90bf21628d257ff052528af1c61bb48f754b3555648f5652fa35d78
  [ "$(/usr/bin/unzip -Z1 "$work/platform-tools.zip" | /usr/bin/grep -Fxc 'platform-tools/adb')" = 1 ] || fail 'Android 도구의 adb를 확인하지 못했습니다.'
  /usr/bin/unzip -p "$work/platform-tools.zip" platform-tools/adb > "$work/adb" || fail 'Android adb 추출에 실패했습니다.'
  [ -s "$work/adb" ] || fail 'Android adb가 비어 있습니다.'
  /bin/chmod 700 "$work/adb"; adb="$work/adb"
 fi
 [ ! -L "$keydir" ] || fail 'ADB 키 폴더가 심볼릭 링크입니다.'
 mkdir -p "$keydir"
 "$adb" keygen "$private" || fail 'ADB 키 생성에 실패했습니다.'
fi
[ -f "$key" ] && [ ! -L "$key" ] || fail 'ADB 공개키를 읽을 수 없습니다.'
/usr/bin/perl -MMIME::Base64 -e '
use strict; use warnings; my $p=shift; open my $f,"<",$p or die "key read: $!\n"; local $/; my $r=<$f>; length($r)<=2047 && $r !~ /\0/ or die "invalid ADB key\n"; $r =~ s/\r?\n\z//; $r !~ /[\r\n]/ or die "invalid ADB key\n"; my ($b)=$r =~ /^(\S+)/; defined($b) && $b =~ /^[A-Za-z0-9+\/]+={0,2}$/ or die "invalid ADB key\n"; my $d=decode_base64($b); length($d)==524 && unpack("V",substr($d,0,4))==64 && unpack("V",substr($d,520,4))==65537 or die "invalid ADB key\n"; print "$r\n";' "$key" > "$work/public-key" || fail 'ADB 공개키 형식이 올바르지 않습니다.'
keyhash=$(/usr/bin/shasum -a 256 "$work/public-key" | /usr/bin/awk '{print $1}')
/usr/bin/perl -MDigest::SHA=sha1 -e '
use strict; use warnings; my $name="neev-adb-public-sha256:".shift; my $ns=pack("H*","6ba7b8119dad11d180b400c04fd430c8"); my $h=sha1($ns.$name); substr($h,6,1)=chr((ord(substr($h,6,1)) & 15) | 80); substr($h,8,1)=chr((ord(substr($h,8,1)) & 63) | 128); my $x=unpack("H*",substr($h,0,16)); print join("-",substr($x,0,8),substr($x,8,4),substr($x,12,4),substr($x,16,4),substr($x,20,12)),"\n";' "$keyhash" > "$work/installation.id"
adbzip="$work/USB_READY/packages/local.neev.dongle-adb-1.0.5.zip"
[ -f "$adbzip" ] && [ ! -L "$adbzip" ] || fail 'ADB 패키지를 찾지 못했습니다.'
/usr/bin/unzip -tqq "$adbzip" >/dev/null || fail 'ADB 패키지가 손상되었습니다.'
/usr/bin/unzip -Z1 "$adbzip" > "$work/adb-list"
! /usr/bin/grep -Eq '^(runtime/mac\.pub|installation\.id)$' "$work/adb-list" || fail 'ADB 템플릿에 기존 키가 있습니다.'
mkdir "$work/key-files" "$work/key-files/runtime"
cp "$work/public-key" "$work/key-files/runtime/mac.pub"
cp "$work/installation.id" "$work/key-files/installation.id"
(cd "$work/key-files" && /usr/bin/zip -q "$adbzip" runtime/mac.pub installation.id) || fail 'ADB 공개키를 추가하지 못했습니다.'
md5=$(/sbin/md5 -q "$adbzip")
wrapper="$work/USB_READY/NEMESIS.sh"
[ "$(/usr/bin/grep -o '@PERSONAL_ADB_MD5@' "$wrapper" | /usr/bin/wc -l | /usr/bin/tr -d ' ')" = 1 ] || fail '설치 스크립트가 예상과 다릅니다.'
/usr/bin/sed "s/@PERSONAL_ADB_MD5@/$md5/" "$wrapper" > "$work/NEMESIS.sh"
cp "$work/NEMESIS.sh" "$wrapper"
/usr/bin/unzip -tqq "$adbzip" >/dev/null || fail 'ADB 패키지 검증에 실패했습니다.'
mv "$work/USB_READY" "$out" || fail '설치 파일을 완성하지 못했습니다.'
printf '설치 파일 준비 완료. ADB 식별자: %s\n' "$(cat "$work/installation.id")"
