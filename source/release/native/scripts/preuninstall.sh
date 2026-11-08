#!/system/bin/sh
# NEMESIS preuninstall for a HMG-API mod package (full root, a child of the nemesis
# process): removes the mod from HMG-API and applies, so everything it changed goes
# back to stock (its settings stay in /data/hmgapi/settings, as with Remove in the
# HMG-API app). A failure here keeps NEMESIS's package.
# NEMESIS also runs it before an update, with the new package already staged in
# /data/local/tmp/nemesis/stage/<id> (native/pkg.c cmd_install): then it does
# nothing, and the new postinstall updates the mod in place. Never when the nemesis
# command running this is an uninstall: a stage left by an interrupted install
# mustn't turn an uninstall into nothing.
# HMG-API's NEMESIS sync (HMGAPI_NEMESIS_SYNC=1) unlists or re-lists only: HMG-API did (or
# keeps) the real work, and the mod stays as it is in HMG-API.
umask 077
H=${ROOT:-}/system/bin/hmgapid
ID=$(cat mod/id)
if [ "$HMGAPI_NEMESIS_SYNC" = 1 ]; then echo "HMG-API's NEMESIS sync: list only, $ID stays as it is in HMG-API"; exit 0; fi
PKG=hmgapi.$ID
STAGE=${ROOT:-}/data/local/tmp/nemesis/stage/$PKG
caller=$(cat /proc/$PPID/cmdline 2>/dev/null)
case $caller in
    *uninstall*) ;;
    *)
        if grep -q "^id=$PKG\$" "$STAGE/NEMESIS.data" 2>/dev/null; then
            echo "update: $ID stays installed, the new package updates it"
            exit 0
        fi ;;
esac
M=${ROOT:-}/data/hmgapi/mods/$ID
[ -d "$M" ] || { echo "$ID isn't installed in HMG-API (removed in the app?): nothing to do"; exit 0; }
if [ ! -x "$H" ]; then
    # HMG-API was uninstalled: its uninstall already put every file back to stock, but
    # kept /data/hmgapi; drop the mod so a reinstalled HMG-API doesn't bring it back
    rm -rf "$M"
    echo "HMG-API isn't installed: removed $ID from /data/hmgapi/mods"
    exit 0
fi
"$H" ping >/dev/null 2>&1 || "$H" daemon >/dev/null 2>&1
"$H" engine remove "$ID" --from-nemesis || { echo "HMG-API didn't remove $ID (see above): nothing changed, the package stays"; exit 1; }
# Removed from HMG-API either way now, so the package goes even when the apply fails:
# the HMG-API app shows the pending change and its problem.
if ! "$H" engine apply; then
    echo "WARNING: $ID is removed from HMG-API, but its files aren't back to stock yet (see above): open the HMG-API app and tap Apply changes"
    exit 0
fi
echo "removed $ID; restart the head unit if HMG-API says so above"
exit 0
