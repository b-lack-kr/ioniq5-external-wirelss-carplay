// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

/** Observes inputs only; never participates in the power decision. */
final class PowerInputTrace {
    private int ignition=-1,ignitionLive=-1,acc=-1,accLive=-1,driverDoor=-1;
    private int raw=-1,getterOff=-1,preparing=-1,micomEvent=-1;
    private int polls,pollFailures,ignitionEvents,accEvents,doorEvents,micomEvents,prepareEvents,offEvents;
    private long ignitionAt=-1,accAt=-1,doorAt=-1,lastLog=-1;
    private String lastState="";
    private static int next(int value){return value==Integer.MAX_VALUE?value:value+1;}
    void poll(int value,boolean off,boolean prepare){raw=value;getterOff=off?1:0;preparing=prepare?1:0;polls=next(polls);}
    void pollFailed(){raw=getterOff=preparing=-1;pollFailures=next(pollFailures);}
    void ignition(boolean on,boolean live,long time){ignition=on?1:0;ignitionLive=live?1:0;ignitionAt=time;ignitionEvents=next(ignitionEvents);}
    void acc(int value,boolean live,long time){acc=value;accLive=live?1:0;accAt=time;accEvents=next(accEvents);}
    void door(int which,int value,long time){doorEvents=next(doorEvents);if(which==1&&(value==0||value==1)){driverDoor=value;doorAt=time;}}
    void micom(int value){micomEvent=value;micomEvents=next(micomEvents);}
    void systemOff(boolean prepare){if(prepare)prepareEvents=next(prepareEvents);else offEvents=next(offEvents);}
    private static long age(long now,long previous){return previous<0?-1:Math.max(0,(now-previous)/1000000L);}
    String summary(long now,String state,String reason,long decision){
        long gap=lastLog<0?Long.MAX_VALUE:Math.max(0,(now-lastLog)/1000000L);
        // Changed decisions are visible promptly; flapping cannot exceed one line/second.
        if(lastLog>=0&&(gap<1000||(gap<10000&&state.equals(lastState))))return null;
        String out="AP_POWER_INPUT elapsed_ms="+(now/1000000L)+" state="+state+" reason="+reason
                +" decision_ns="+decision+" micom_raw="+raw+" getter_off="+getterOff+" preparing="+preparing
                +" ignition="+ignition+" ignition_live="+ignitionLive+" ignition_age_ms="+age(now,ignitionAt)
                +" acc_raw="+acc+" acc_live="+accLive+" acc_age_ms="+age(now,accAt)
                +" driver_door="+driverDoor+" driver_door_age_ms="+age(now,doorAt)+" micom_event_raw="+micomEvent
                +" polls="+polls+" poll_failures="+pollFailures+" ignition_events="+ignitionEvents
                +" acc_events="+accEvents+" door_events="+doorEvents+" micom_events="+micomEvents
                +" prepare_events="+prepareEvents+" off_events="+offEvents;
        polls=pollFailures=ignitionEvents=accEvents=doorEvents=micomEvents=prepareEvents=offEvents=0;
        lastLog=now;lastState=state;return out;
    }
}
