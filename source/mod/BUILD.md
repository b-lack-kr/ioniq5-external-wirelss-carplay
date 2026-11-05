# IONIQ 5 USB CarPlay 1.0.1

The module targets Korean NEEV firmware 260902 and Android 4.4.2. The source
contains the Android CarPlay bridge, 5 GHz AP supervisor, HMG-API memory
patch recipes, compile-only vendor interfaces and local Realtek interface
classification assembly. The recipes alter only a matching process in memory.

## Building the mod

Use a JDK 17 installation, Python 3, Android SDK platform 34 android.jar, a
standalone R8 JAR providing D8, and the HMG-API 1.0.1 source tree. The build
targets Dalvik DEX 035 and Android API 19. Run this command in this folder.

```sh
python3 build.py --java-home /path/to/jdk-17 --android-jar /path/to/android.jar \
  --d8 /path/to/r8.jar --hmg-source /path/to/HMG-API-1.0.1 --out /path/to/empty-build
```

The resulting payload/ directory is an unsigned mod. To sign your own build,
pass --sign-key pointing to your own HMG-API author key. The published
installer verifies the release author's signature. Builds signed by someone
else require their own installer owner pins. The mod neither includes nor
derives any user's hotspot credentials; credentials are provided at install.

The AP needs three owner-provided files from the HKG_5W_USB_Wlan v2.0.2
release, external to this mod. The installer/importer verifies them before
placing them in its protected vendor directory. Stock hostapd from the head
unit is adapted locally. Runtime only configures the selected Realtek USB
interface on 5 GHz channel 36. The included interfaces in stubs/ are for
compilation only. They are not included in hooks.jar or provider.jar.

Run `python3 tests/test_ap.py` to exercise the supervisor in a simulated target
filesystem with fake interfaces and kernel commands. To independently rebuild
the local adapter transport recipe, run
`python3 native/build_recipe.py /path/to/libCarPlayCoreUtils.so --output /path/to/new.recipe`
with an ARM-capable clang. The tool verifies the exact target library hash.
