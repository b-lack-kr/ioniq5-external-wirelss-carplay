package kr.ioniq5.usbcp;
import java.io.*;
public final class HmgApSupervisorTest {
    static void u32(OutputStream out,int n)throws IOException{out.write(n);out.write(n>>>8);out.write(n>>>16);out.write(n>>>24);}
    static void frame(ByteArrayOutputStream b,int kind,byte[] data)throws IOException{b.write(kind);u32(b,data.length);b.write(data);}
    static void denied(byte[] bytes)throws Exception{
        try{HmgApSupervisor.response(new DataInputStream(new ByteArrayInputStream(bytes)));throw new AssertionError();}
        catch(IOException expected){}
    }
    public static void main(String[] args)throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        frame(b,'O',"start neev-usb-carplay/usb-ap: asked\n".getBytes("US-ASCII"));
        frame(b,'X',new byte[4]);
        String result=HmgApSupervisor.response(new DataInputStream(new ByteArrayInputStream(b.toByteArray())));
        if(!result.equals("start neev-usb-carplay/usb-ap: asked\n"))throw new AssertionError();
        b.reset();frame(b,'X',new byte[]{13,0,0,0});denied(b.toByteArray());
        b.reset();b.write('O');u32(b,8193);denied(b.toByteArray());
        b.reset();b.write('O');u32(b,-1);denied(b.toByteArray());
        b.reset();frame(b,'Z',new byte[0]);denied(b.toByteArray());
        b.reset();frame(b,'X',new byte[3]);denied(b.toByteArray());
        b.reset();frame(b,'O',new byte[2]);denied(b.toByteArray());
        b.reset();for(int n=0;n<65;n++)frame(b,'O',new byte[0]);denied(b.toByteArray());
        System.out.println("HmgApSupervisor: bounded protocol checks passed");
    }
}
