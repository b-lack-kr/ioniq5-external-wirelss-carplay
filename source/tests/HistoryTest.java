// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp.setup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

public final class HistoryTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError("history check"); }
    static File file(File parent, String name, String content) throws Exception {
        File f = new File(parent, name);
        Files.write(f.toPath(), content.getBytes("UTF-8"));
        return f;
    }
    static String read(File parent, String name) throws Exception {
        return new String(Files.readAllBytes(new File(parent, name).toPath()), "UTF-8");
    }
    static File result(File root, int n) {
        File out = new File(root, "NEEV_BUNDLE_RESULT_20261105_002959_" + n);
        check(out.mkdir());
        return out;
    }
    static final String STAMP = "11-05 00:29:59.321 ";
    static final String HEADER = STAMP + "I/HMG-API (  101): [neev-usb-carplay] ";
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        File before = file(root, "provider.log.1",
                "wall_ms=101 elapsed_ms=200 CP_PROVIDER version=1.0.0 event=started\n" +
                "wall_ms=102 elapsed_ms=300 CP_OBSERVER event=unavailable phase=net_identity kind=private\n");
        File provider = file(root, "provider.log",
                "wall_ms=103 elapsed_ms=12 CP_PROVIDER event=ready age_ms=900\n" +
                "wall_ms=104 elapsed_ms=13 CP_USB_ID event=seen id=private\n" +
                "wall_ms=105 elapsed_ms=14 CP_OBSERVER event=retry phase=private\n" +
                "wall_ms=106 elapsed_ms=15 CP_OBSERVER event=recovered failures=3\n");
        StringBuilder records = new StringBuilder();
        for (int i=0; i<350; i++) records.append("AP_EVENT state=WAIT_DONGLE\n");
        File ap = file(root, "daemon_stdout.log", records + "AP_EVENT state=SERVICES_RUNNING\n" +
                "AP_EVENT state=SECRET\nAP_EVENT state=WAIT_DONGLE PRIVATE\nAP_EVENT state=STARTING");
        File input = file(root, "logcat-input",
                HEADER + "USB_CP_CREDENTIALS_SENT frequency=5180 PRIVATE\n" +
                HEADER + "bridge: CarPlay session started transport=1 PRIVATE\n" +
                HEADER + "bridge: CarPlay session created (usb=0 btAddr=00:11:22:33:44:55)\n" +
                HEADER + "bridge: CarPlay session finalized type=1 PRIVATE\n" +
                HEADER + "bridge: metadata PRIVATE USB_CP_MEDIA\n" +
                HEADER + "Title: CarPlay session started transport=5 PRIVATE\n" +
                HEADER + "USB_CP_SECRETCODE PRIVATE\n" +
                HEADER.replace("neev-usb-carplay","other") + "USB_CP_IAP_ATTACH PRIVATE\n");
        File logcat = file(root, "logcat",
                "#!/bin/sh\n[ \"$*\" = '-d -v time -s HMG-API:I *:S' ] || exit 9\n" +
                "exec /bin/cat \"\u0024{0%/*}/logcat-input\"\n");
        check(logcat.setExecutable(true));
        File out = result(root,1);
        check(History.collect(out,ap,provider,logcat) == 0);
        check(read(out,"ap-events.txt").split("\n").length == 256);
        check(read(out,"ap-events.txt").endsWith("SERVICES_RUNNING\n"));
        check(read(out,"provider-events.txt").equals(
                "101 200 provider started\n102 300 observer unavailable net_identity\n103 12 provider ready\n106 15 observer recovered\n"));
        check(read(out,"carplay-events.txt").equals(STAMP + "USB_CP_CREDENTIALS_SENT\n" + STAMP +
                "SESSION_STARTED\n" + STAMP + "SESSION_CREATED\n" + STAMP + "SESSION_FINALIZED\n"));
        for (File f : out.listFiles()) {
            String text = read(out,f.getName());
            for (String secret : Arrays.asList("PRIVATE","SECRET","00:11:22:", "btAddr", "metadata", "5180", "CAPTIVE"))
                check(!text.contains(secret));
        }
        try { History.collect(out,ap,provider,logcat); throw new AssertionError("overwrite"); } catch (IOException expected) {}
        check(before.delete());
        check(History.collect(result(root,2),ap,provider,logcat) == 0);
        File unsafe = new File(root,"unsafe");
        Files.createSymbolicLink(unsafe.toPath(),ap.toPath());
        File missingOut = result(root,3);
        check(History.collect(missingOut,unsafe,new File(root,"absent"),new File(root,"missing-logcat")) == 7);
        check(read(missingOut,"history-status.txt").contains("ap=UNSAFE_PATH"));
        check(read(missingOut,"history-status.txt").contains("carplay=MISSING"));
        check(read(missingOut,"history-status.txt").contains("provider_current=MISSING"));
        check(read(missingOut,"carplay-events.txt").isEmpty());
        File failLogcat = file(root,"fail-logcat","#!/bin/sh\nexit 3\n");
        check(failLogcat.setExecutable(true));
        File failOut = result(root,4);
        check(History.collect(failOut,ap,provider,failLogcat)==7 && read(failOut,"history-status.txt").contains("carplay=DUMP_FAILED"));
        File slow = file(root,"slow-logcat","#!/bin/sh\nexec /bin/sleep 15\n");
        check(slow.setExecutable(true));
        File timeoutOut = result(root,5);
        long started=System.nanoTime();
        check(History.collect(timeoutOut,ap,provider,slow)==7 && read(timeoutOut,"history-status.txt").contains("carplay=TIMEOUT"));
        check((System.nanoTime()-started)/1000000 < 7200);
        File supervisor=file(root,"usb-ap.log","AP_STARTUP version=1.0.1 phase=ENTRY\n"+
                "AP_STARTUP_FAILURE reason=PREPARE_PATHS\nAP_DRIVER state=ready family=88x2bu\n"+
                "AP_DRIVER state=ready family=88x2bu SECRET\ncredential=PRIVATE\n");
        File services=file(root,"services.status","neev-usb-carplay usb-ap running 123 0\n"+
                "neev-usb-carplay ap-provider waiting 0 0\nother secret PRIVATE\n");
        File modules=file(root,"modules","88x2bu 1234 0 - Live 0xffffffff\nother PRIVATE 0\n");
        File bootOut=result(root,6);
        History.boot(bootOut,supervisor,services,modules);
        check(read(bootOut,"boot-supervisor.txt").contains("reason=PREPARE_PATHS"));
        check(read(bootOut,"boot-services.txt").split("\n").length==2);
        check(read(bootOut,"boot-drivers.txt").equals("88x2bu LOADED\n"));
        for(File f:bootOut.listFiles())check(!read(bootOut,f.getName()).contains("PRIVATE")&&!read(bootOut,f.getName()).contains("SECRET"));
        System.out.println("HistoryTest PASS: source and prefix ownership, strict categories, caps, rotated files, redaction, no overwrite, missing, failure and timeout");
    }
}
