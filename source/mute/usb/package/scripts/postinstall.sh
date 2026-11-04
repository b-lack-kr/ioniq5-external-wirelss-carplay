#!/system/bin/sh
. ./scripts/common.sh || exit 1
check_install
if has_installed; then
    echo 'CarPlay Mute Pause is already installed; its current on/off setting was kept.'
    exit 0
fi
# The signed mod declares only runtime behavior. Packages.install publishes live state.
# No global apply, activation of a disabled mod, or restart of other mods is needed.
"$H" engine install "$PWD/mod/package.zip" --uuid "$UUID" --from-nemesis || fail INSTALL_FAILED
installed_release || fail INSTALL_VERIFICATION_FAILED
echo 'CarPlay 음소거 일시정지 설치 완료. 내비게이션을 재부팅하세요.'
