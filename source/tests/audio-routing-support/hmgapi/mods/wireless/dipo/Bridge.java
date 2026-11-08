package hmgapi.mods.wireless.dipo;

final class Bridge {
    static Object sCp = new Object();
    static boolean sCpWireless = true, sCpClient = true, sCpConnected = true, sUsb;
    static boolean on = true;
    static boolean enabled() { return on; }
}
