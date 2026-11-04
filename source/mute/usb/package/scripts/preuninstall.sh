#!/system/bin/sh
. ./scripts/common.sh || exit 1
# HMG-API manages its own listing during a sync. NEMESIS also runs this hook
# from the old package on updates: the new postinstall owns that transaction.
[ "$HMGAPI_NEMESIS_SYNC" != 1 ] || exit 0
stage=/data/local/tmp/nemesis/stage/hmgapi.neev-carplay-mute-pause
caller=$(cat /proc/$PPID/cmdline 2>/dev/null)
case "$caller" in
    *uninstall*) ;;
    *) if grep -qx 'id=hmgapi[.]neev-carplay-mute-pause' "$stage/NEMESIS.data" 2>/dev/null; then exit 0; fi ;;
esac
has_installed || exit 0
engine_ready
installed_release || fail REFUSING_TO_REMOVE_DIFFERENT_MOD
"$H" engine remove "$ID" --from-nemesis || fail REMOVE_FAILED
has_installed && fail MOD_STILL_PRESENT
echo 'CarPlay 음소거 일시정지 제거 완료. 내비게이션을 재부팅하세요.'
