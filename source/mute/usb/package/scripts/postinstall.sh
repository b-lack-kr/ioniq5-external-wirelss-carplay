#!/system/bin/sh
. ./scripts/common.sh || exit 1
check_install
if has_installed && current_release; then
    echo 'CarPlay Mute Pause 1.1.0 is already installed; its current on/off setting was kept.'
    exit 0
fi
# check_install verifies any previous release before this replacement. The HMG
# engine keeps its disabled marker and settings; no other mod or global apply is changed.
"$H" engine install "$PWD/mod/package.zip" --uuid "$UUID" --from-nemesis || fail INSTALL_FAILED
current_release || fail INSTALL_VERIFICATION_FAILED
echo 'CarPlay 음소거 일시정지·해제 시 재생 설치 완료. 내비게이션을 재부팅하세요.'
