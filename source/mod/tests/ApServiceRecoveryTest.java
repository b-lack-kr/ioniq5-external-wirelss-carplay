package kr.ioniq5.usbcp;
import java.io.IOException;
public final class ApServiceRecoveryTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static final class Fake implements ApServiceRecovery.Supervisor,ApServiceRecovery.Gate {
        String line="neev-usb-carplay usb-ap stopped 0 0\n";int starts,reads,blocked;boolean offDuringRead;
        public String status(){reads++;if(offDuringRead)blocked=ApServiceRecovery.POWER_OFF;return line;}
        public boolean start(){starts++;return true;}
        public int blocked(){return blocked;}
    }
    public static void main(String[] args)throws Exception {
        for(String state:new String[]{"stopped","failed","exited"}){
            Fake f=new Fake();f.line="neev-usb-carplay usb-ap "+state+" 0 3\n";
            check(new ApServiceRecovery().recover(f,f,1)==ApServiceRecovery.START_REQUESTED&&f.starts==1);
        }
        Fake f=new Fake();ApServiceRecovery p=new ApServiceRecovery();
        f.line="neev-usb-carplay usb-ap running 123 0\n";
        check(p.recover(f,f,1)==ApServiceRecovery.ALREADY_RUNNING&&f.starts==0);
        f.line="neev-usb-carplay usb-ap waiting 0 9\n";
        check(p.recover(f,f,1)==ApServiceRecovery.WAITING&&f.starts==0);
        f.line="neev-usb-carplay usb-ap stopped 123 0\n";
        check(p.recover(f,f,1)==ApServiceRecovery.WAITING&&f.starts==0);
        f.line="neev-usb-carplay ap-provider running 123 0\n";
        check(p.recover(f,f,1)==ApServiceRecovery.MISSING&&f.starts==0);
        f.line="neev-usb-carplay usb-ap running 0 0\n";
        check(p.recover(f,f,1)==ApServiceRecovery.INVALID&&f.starts==0);
        f.line="neev-usb-carplay usb-ap stopped 0 0\n";f.line+=f.line;
        check(p.recover(f,f,1)==ApServiceRecovery.INVALID&&f.starts==0);
        f=new Fake();f.blocked=ApServiceRecovery.DISABLED;
        check(p.recover(f,f,1)==ApServiceRecovery.DISABLED&&f.reads==0&&f.starts==0);
        f=new Fake();f.offDuringRead=true;
        check(p.recover(f,f,1)==ApServiceRecovery.POWER_OFF&&f.starts==0);
        f=new Fake();
        check(p.recover(f,f,10)==ApServiceRecovery.START_REQUESTED&&f.starts==1);
        check(p.recover(f,f,20)==ApServiceRecovery.THROTTLED&&f.starts==1);
        check(p.recover(f,f,10010)==ApServiceRecovery.START_REQUESTED&&f.starts==2);
        System.out.println("ApServiceRecovery: all checks passed");
    }
}
