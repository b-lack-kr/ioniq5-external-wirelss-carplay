// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

/** Read-only OEM state policy, independent of Android so ordering is testable. */
final class RetainedPower {
    String state = "UNKNOWN", reason = "UNKNOWN";
    long decisionNanos;
    private Boolean ignition;
    private boolean ignitionOffConfirmed, driverDoorOpen;
    private Integer micomStatus, bcmStatus;
    private boolean micomCallbackSeen;

    void restore(String saved, long nanos) {
        if (("OFF".equals(saved) || "ON".equals(saved)) && nanos >= 0) {
            state = saved; decisionNanos = nanos; reason = "RESTORED";
        }
    }
    private void decide(boolean off, String why, long now) { decide(off, why, now, false); }
    private void decide(boolean off, String why, long now, boolean event) {
        String next = off ? "OFF" : "ON";
        // Heartbeats/repeated cached values must not become new power events.
        if (now >= decisionNanos && (event || !next.equals(state))) {
            state = next; decisionNanos = now; reason = why;
        }
    }
    private void doorGate(long now) {
        if (ignitionOffConfirmed && driverDoorOpen)
            decide(true, "IGN_OFF_DRIVER_DOOR", now);
    }
    void ignition(boolean on, boolean live, long now) {
        Boolean prior = ignition;
        ignition = on;
        if (on) {
            ignitionOffConfirmed = false;
            if (live && !Boolean.TRUE.equals(prior)) decide(false, "IGN_ON", now, true);
        } else if (live && Boolean.TRUE.equals(prior)) {
            // BCMManager replays its initial unknown (-1) as false at listener
            // registration. Require a real observed ON->OFF transition.
            ignitionOffConfirmed = true;
        }
        doorGate(now);
    }
    void door(int which, int opened, long now) {
        // Exact BCM DoorLocationType.FRONT_LEFT=1 (Korean left-hand-drive car).
        if (which != 1 || (opened != 0 && opened != 1)) return;
        driverDoorOpen = opened == 1;
        doorGate(now);
    }
    void micomCallback(int raw, long now) {
        // MicomPowerService.registerPowerStatusListener sends one queued state
        // snapshot before normal events. It is not a new vehicle ON transition.
        if (!micomCallbackSeen) { micomCallbackSeen = true; return; }
        micom(raw, false, false, true, now);
    }
    void micom(int raw, boolean avOff, boolean preparing, boolean live, long now) {
        Integer previous = micomStatus;
        micomStatus = raw;
        // Micom AccStatus: ACCON=0, DELAYMODE=6, PRESYSTEMOFF=7, OFF=255.
        // Do not reuse BCM's different enum or equate DELAYMODE with OFF.
        if (preparing || raw == 7 || raw == 255 || avOff) {
            decide(true, preparing ? "PREPARE_SYSTEM_OFF" : "MICOM_OFF", now);
        } else if (raw == 0 && (previous == null ? live : previous != 0)) {
            ignitionOffConfirmed = false;
            decide(false, "MICOM_ACC_ON", now, live);
        } else if ("UNKNOWN".equals(state)) {
            // A startup getter is a baseline, not a new ON authorization.
            state = "ON"; decisionNanos = 0; reason = "INITIAL_READING";
        }
        doorGate(now);
    }
    void bcmAcc(int raw, boolean live, long now) {
        Integer previous = bcmStatus;
        bcmStatus = raw;
        if (!live) return; // synchronous replay may still contain defaults
        // BCM AccBcmStatus: OFF=0, PRESYSTEMOFF=1, DELAYMODE=3, ON=5.
        if (raw == 0 || raw == 1) decide(true, "BCM_OFF", now, true);
        else if (raw == 5 && (previous == null || previous != 5)) {
            ignitionOffConfirmed = false;
            decide(false, "BCM_ACC_ON", now, true);
        }
    }
    void systemOff(boolean preparing, long now) {
        decide(true, preparing ? "PREPARE_SYSTEM_OFF" : "SYSTEM_OFF", now, true);
    }
}
