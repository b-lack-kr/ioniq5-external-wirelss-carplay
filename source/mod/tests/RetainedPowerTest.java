// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

public final class RetainedPowerTest {
    private static int checks;
    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        RetainedPower p = new RetainedPower();
        check(p.state.equals("UNKNOWN"), "unknown boot leaves normal AP policy unchanged");
        p.ignition(false, false, 1); p.door(1, 1, 2);
        check(!p.state.equals("OFF"), "BCM cached unknown/false plus open door is not confirmed OFF");
        p.micom(0, false, false, false, 3);
        check(p.state.equals("ON") && p.decisionNanos == 0, "initial getter is baseline not new ON");
        p.ignition(true, true, 4); p.door(1, 1, 5);
        check(p.state.equals("ON"), "door opening while ignition ON preserves AP");
        p.door(1, 0, 6); p.ignition(false, true, 7); p.micom(6, false, false, false, 8);
        check(p.state.equals("ON"), "ignition OFF and retained ACC/DELAY keep AP until driver door");
        p.door(2, 1, 9);
        check(p.state.equals("ON"), "passenger door alone does not end retained session");
        p.door(1, 1, 10);
        check(p.state.equals("OFF") && p.decisionNanos == 10, "driver door ends confirmed ignition-OFF session");
        p.micom(6, false, false, false, 11); p.door(1, 0, 12);
        check(p.state.equals("OFF") && p.decisionNanos == 10, "false getter and closing door cannot revive AP");
        RetainedPower restarted = new RetainedPower();
        restarted.restore(p.state, p.decisionNanos);
        restarted.micom(0, false, false, false, 13);
        restarted.ignition(true, false, 14); restarted.bcmAcc(5, false, 15);
        check(restarted.state.equals("OFF") && restarted.decisionNanos == 10, "reader restart and cached ON preserve OFF");
        restarted.ignition(false, true, 15); restarted.ignition(true, true, 16);
        check(restarted.state.equals("ON") && restarted.decisionNanos == 16, "new live ignition ON resumes");
        restarted.micom(0, false, false, false, 100);
        check(restarted.decisionNanos == 16, "getter heartbeat must not renew ON timestamp");
        restarted.bcmAcc(3, true, 100); restarted.systemOff(true, 101);
        check(restarted.state.equals("OFF"), "prepare system OFF is authoritative");
        restarted.ignition(true, true, 99);
        check(restarted.state.equals("OFF"), "late queued stale ON cannot clear newer OFF");
        restarted.bcmAcc(5, true, 102);
        check(restarted.state.equals("ON"), "BCM ON uses its own enum, 5");
        restarted.bcmAcc(3, true, 103);
        check(restarted.state.equals("ON"), "BCM DELAYMODE 3 is retained");
        restarted.bcmAcc(1, true, 104);
        check(restarted.state.equals("OFF"), "BCM PRESYSTEMOFF 1 differs from Micom LOGICON 1");
        RetainedPower fresh = new RetainedPower();
        fresh.micom(6, false, false, false, 200);
        check(fresh.state.equals("ON"), "Micom DELAYMODE 6 alone is retained");
        fresh.micom(7, false, false, false, 201);
        check(fresh.state.equals("OFF"), "Micom PRESYSTEMOFF 7 stops");
        fresh.micom(0, false, false, false, 202);
        check(fresh.state.equals("ON"), "observed Micom OFF to ACCON transition resumes");
        fresh.ignition(true, true, 203);
        check(fresh.decisionNanos == 203, "live new ON is newer even if a getter already said ON");
        fresh.ignition(true, true, 204);
        check(fresh.decisionNanos == 203, "duplicate ignition ON does not renew timestamp");
        fresh.micom(0, false, false, true, 205);
        check(fresh.decisionNanos == 203, "duplicate Micom ON does not renew timestamp");
        RetainedPower restored = new RetainedPower(); restored.restore("OFF", 300);
        restored.bcmAcc(5, false, 301); restored.bcmAcc(5, true, 302);
        check(restored.state.equals("OFF"), "cached BCM ON followed by duplicate ON preserves OFF");
        restored.bcmAcc(3, true, 303); restored.bcmAcc(5, true, 304);
        check(restored.state.equals("ON") && restored.decisionNanos == 304, "real BCM DELAY to ON resumes");
        RetainedPower replay = new RetainedPower(); replay.restore("OFF", 400);
        replay.micomCallback(0, 401);
        check(replay.state.equals("OFF") && replay.decisionNanos == 400, "queued first Micom snapshot cannot resume restored OFF");
        replay.micom(0, false, false, false, 402); replay.micomCallback(0, 403);
        check(replay.state.equals("OFF"), "initial getter and repeated cached Micom ON cannot resume");
        replay.micomCallback(6, 404); replay.micomCallback(0, 405);
        check(replay.state.equals("ON") && replay.decisionNanos == 405, "subsequent genuine Micom DELAY to ON resumes");
        System.out.println("RetainedPowerTest: " + checks + " assertions passed");
    }
}
