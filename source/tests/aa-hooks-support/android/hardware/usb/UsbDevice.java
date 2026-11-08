package android.hardware.usb;
public final class UsbDevice {
    final int[] types;public UsbDevice(int...types){this.types=types;}
    public int getInterfaceCount(){return types.length;}
    public UsbInterface getInterface(int index){return new UsbInterface(types[index]);}
    public String getDeviceName(){throw new AssertionError("device identity must never be logged");}
}
