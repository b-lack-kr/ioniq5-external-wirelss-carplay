package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.text.format.DateFormat;

import com.tcc.iap2.Iap2NotiMsg;

import java.util.Calendar;
import java.util.LinkedHashMap;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * CarPlay turn-by-turn on the cluster and HUD: a port of the turn-by-turn build's DiPO
 * route guidance manager (e/u, with its route update e/c and maneuver e/v), fed by
 * HMG-API's own iAP2 listener instead of DiPO's, and sending through HMG-API's Cluster API.
 * DiPO's own manager only exists when stepofspec is 999 or more, and stays off.
 *
 * The iPhone sends RouteGuidanceUpdate (120: state, distances, the current maneuver
 * list), RouteGuidanceManeuverUpdate (121: one maneuver by index) and LaneGuidance
 * (122), each for a display component, often field by field. Kept per component as in
 * DiPO. What it sends, as the build did:
 *   - the turn (updateTBTInfo: icon, distance in the phone's units), crossroad name and,
 *     when state is 6, the destination name
 *   - the HUD list (0xF2 frame: icon, next icon, road) and the HUD distance (2C)
 *   - remaining distance and arrival time (updateTBTSubInfo, in the owner's units)
 *   - up to 3 upcoming turns (updateTPTurnList, owner's units) and lanes (0xF1 frame)
 * Never the cluster's alive or operation status: phone guidance doesn't claim the
 * cluster (the cluster doesn't come back from that on these cars).
 * Also from the build: when the source app changes (Apple Maps to Waze, say), what the
 * old app left is cleared and route guidance restarted, so the phone resends all of it.
 * Not ported: DiPO's per-region icon filters (China, Europe, Middle East, Australia,
 * Brazil); on US and Canada cars the build sends every icon.
 * Route state stays on one handler. Optional lane/pattern IPC uses one bounded
 * latest-state worker, so those calls cannot block basic main/HUD processing.
 */
final class CpTurns {
    final Context context;
    final Cluster cluster;
    final boolean standardTurns;
    final PatternSink patterns;
    interface PatternSink { boolean send(int[] values); }
    interface LaneSink { boolean send(byte[] frame); }
    // ABI only for the retained, inactive v1.0.12 helper source; CP no longer creates it.
    interface ApproachSink { void update(boolean active, int bar); }
    private final CpAuxWriter auxiliary;
    private volatile boolean auxiliaryAllowed=true;
    private volatile long auxiliaryEpoch;
    private volatile long laneInputGeneration;
    private int auxiliaryComponent=-1, auxiliaryManeuver=-1, auxiliaryLane=-1;
    private boolean auxiliaryStarted;
    private boolean laneWasWritten; // Auxiliary worker only.
    private int auxiliaryErrors;
    // Per-kind rolling samples keep development evidence throughout a long trip,
    // rather than exhausting a first-12-record budget before the fault occurs.
    private final long[] diagnosticAt=new long[7];
    private final int[] diagnosticDropped=new int[7];
    private int diagnosticSeen;
    private static final String[] DIAGNOSTIC_KIND={"route","maneuver","lane_input","lane_select","distance_wait","lane_write","pattern_write"};
    private synchronized boolean diagnosticReady(int kind) {
        long now=laneNow(); int bit=1<<kind;
        if((diagnosticSeen&bit)!=0 && now>=diagnosticAt[kind] && now-diagnosticAt[kind]<1000L) {
            if(diagnosticDropped[kind]<Integer.MAX_VALUE) diagnosticDropped[kind]++;
            return false;
        }
        diagnosticSeen|=bit; diagnosticAt[kind]=now; return true;
    }
    private synchronized void diagnostic(int kind,String values) {
        try { Dipo.log("USB_CP_TBT_DIAG kind="+DIAGNOSTIC_KIND[kind]+" "+values+" dropped="+diagnosticDropped[kind]); }
        catch(Throwable ignored) {} // Diagnostics never own guidance success.
        diagnosticDropped[kind]=0;
    }
    private static String numbers(int[] values) {
        if(values==null || values.length==0) return "-1";
        StringBuilder out=new StringBuilder();
        for(int i=0;i<values.length && i<128;i++) { if(i>0) out.append(',');out.append(values[i]); }
        return out.toString();
    }
    private static String frameNumbers(byte[] values) {
        int[] ints=new int[values.length];for(int i=0;i<values.length;i++)ints[i]=values[i]&255;
        return numbers(ints);
    }
    /** restarts the phone's route guidance updates (Stop, then Start) on a thread of its own */
    final Runnable restart;
    /** component id -> its route guidance state (DiPO e/u.Al) */
    final LinkedHashMap<Integer, Update> updates = new LinkedHashMap<Integer, Update>();
    /** component id -> maneuver index -> maneuver (DiPO e/u.Am) */
    final LinkedHashMap<Integer, LinkedHashMap<Integer, Maneuver>> maneuvers = new LinkedHashMap<Integer, LinkedHashMap<Integer, Maneuver>>();
    /** Callback records stay scoped to the display, selected guidance and maneuver. */
    final LinkedHashMap<Integer, LinkedHashMap<Integer, LaneRecord>> laneFrames = new LinkedHashMap<Integer, LinkedHashMap<Integer, LaneRecord>>();
    static final int MAX_LANE_FRAMES = 32, MAX_LANE_COMPONENTS = 8;
    static final long MAX_LANE_AGE_MS = 120000L;
    static final class LaneRecord {
        final CpLaneCallback.Rows rows;
        final long received;
        final boolean oem;
        int maneuver=-1;
        boolean claimed;
        LaneRecord(CpLaneCallback.Rows rows,long received,boolean oem) { this.rows=rows; this.received=received; this.oem=oem; }
    }
    String source;
    private final java.util.LinkedHashSet<String> missingManeuvers = new java.util.LinkedHashSet<String>();
    volatile boolean outputAllowed = true;
    boolean displayed;
    int turnOwner = -1, tripOwner = -1;
    Boolean lastDistanceAvailable;
    boolean hudHasDistance;
    String lastDestination, lastRoad;
    int lastPrimaryIcon = -1, lastPrimaryUnit, lastHudUnit = -1, lastHudScaled;
    float lastPrimaryDirection;
    int lastPrimaryBar, lastPrimarySide;
    int lastPrimaryComponent = -1, lastPrimaryManeuver = -1;
    float lastPrimaryValue;
    boolean lastPrimaryValid;
    boolean primaryTraced;
    long lastPrimaryTraceAt;
    private final LinkedHashMap<Integer, PhaseSample> phaseSeen = new LinkedHashMap<Integer, PhaseSample>();
    private PhaseSample pendingPhase;
    private final long[] phaseLogTimes = new long[8];
    private int phaseLogCount, phaseLogNext, droppedPhases;
    static final long TRIP_INTERVAL_MS = 1000;
    final TripScheduler scheduler;
    TripFrame lastTrip, pendingTrip;
    Runnable tripFlush;
    long lastTripAt;
    Object scheduledOwner;
    int pendingComponent;

    interface TripScheduler {
        long now();
        void later(Runnable work, long delay);
        void cancel(Runnable work);
        // A new identity for every replacement or terminated guidance session.
        Object owner();
    }
    /** last HUD list sent (type, next, road), so an unchanged one isn't resent */
    int hudType, hudNext;
    String hudRoad;

    CpTurns(Context context, Cluster cluster, Runnable restart, TripScheduler scheduler) {
        this(context, cluster, restart, scheduler, false);
    }

    CpTurns(Context context, Cluster cluster, Runnable restart, TripScheduler scheduler, boolean standardTurns) {
        this(context, cluster, restart, scheduler, standardTurns, null);
    }

    CpTurns(Context context, Cluster cluster, Runnable restart, TripScheduler scheduler,
            boolean standardTurns, PatternSink patterns) {
        this(context, cluster, restart, scheduler, standardTurns, patterns, backgroundAuxiliary());
    }

    /** Production wiring provides a method-return-aware lane writer. */
    CpTurns(Context context,Cluster cluster,Runnable restart,TripScheduler scheduler,
            boolean standardTurns,PatternSink patterns,LaneSink laneSink) {
        this(context,cluster,restart,scheduler,standardTurns,patterns,backgroundAuxiliary(),laneSink);
    }

    /** Recording Cluster adapter used by the baseline host harness. The product
     * constructor in Dipo explicitly supplies NativeLaneWriter instead. */
    CpTurns(Context context, final Cluster cluster, Runnable restart, TripScheduler scheduler,
            boolean standardTurns, final PatternSink patterns, CpAuxWriter.Executor executor) {
        this(context,cluster,restart,scheduler,standardTurns,patterns,executor,new LaneSink() {
            public boolean send(byte[] frame) { cluster.laneInfo(frame); return true; }
        });
    }

    CpTurns(Context context, final Cluster cluster, Runnable restart, TripScheduler scheduler,
            boolean standardTurns, final PatternSink patterns, CpAuxWriter.Executor executor,final LaneSink laneSink) {
        this.scheduler=scheduler; this.context=context; this.cluster=cluster;
        this.restart=restart; this.standardTurns=standardTurns; this.patterns=patterns;
        auxiliary=executor==null ? null : new CpAuxWriter(executor, new CpAuxWriter.Sink() {
            public boolean lane(byte[] frame) {
                // Don't add an unsolicited lane clear before this instance owns any lanes.
                if((frame[1]&255)==0 && !laneWasWritten) return true;
                boolean returned=false;
                try {
                    returned=laneSink!=null && laneSink.send(frame);
                    if(returned)laneWasWritten=(frame[1]&255)>0;
                    return returned;
                } finally {
                    if(diagnosticReady(5)) diagnostic(5,"returned="+(returned?1:0)+" frame="+frameNumbers(frame));
                }
            }
            public boolean pattern(int[] frame) {
                if(patterns==null)return true;
                boolean returned=false;
                try { returned=patterns.send(frame);return returned; }
                finally { if(diagnosticReady(6)) diagnostic(6,"returned="+(returned?1:0)+" frame="+numbers(frame)); }
            }
        }, new CpAuxWriter.Gate() {
            public boolean allowed(long epoch) { return outputAllowed && auxiliaryAllowed && auxiliaryEpoch==epoch; }
        }, new CpAuxWriter.Audit() {
            public void failed(String channel) { auxiliaryFailure(channel); }
        });
    }

    private static CpAuxWriter.Executor backgroundAuxiliary() {
        try {
            android.os.HandlerThread thread=new android.os.HandlerThread("hmgapi-cp-aux");
            thread.start();
            final android.os.Handler handler=new android.os.Handler(thread.getLooper());
            return new CpAuxWriter.Executor() {
                public boolean post(Runnable work) { return handler.post(work); }
            };
        } catch(Throwable failed) {
            try { Dipo.log("USB_CP_TBT_AUX_FAILED channel=start"); } catch(Throwable ignored) {}
            return null; // Optional output must not prevent basic TBT construction.
        }
    }

    private synchronized void auxiliaryFailure(String channel) {
        if(auxiliaryErrors++>=12) return;
        try { Dipo.log("USB_CP_TBT_AUX_FAILED channel="+channel); } catch(Throwable ignored) {}
    }

    private long laneNow() { return scheduler==null ? System.nanoTime()/1000000L : scheduler.now(); }

    /** RouteGuidanceUpdate (DiPO e/u.c) */
    void routeGuidance(Iap2NotiMsg msg) {
        if (msg == null) return;
        // A source switch clears cached fields, but the display component remains the
        // same physical recipient when a componentless delta refers to a known display.
        int[] ids = componentIds(msg);
        java.util.Set<Integer> known = ids == null ? knownComponents() : null;
        sourceCheck(msg);
        if (ids == null) {
            for (Integer id : known) storeRoute(id, msg);
        } else {
            for (int id : ids) storeRoute(id, msg);
        }
    }

    private java.util.Set<Integer> knownComponents() {
        java.util.Set<Integer> known = new java.util.LinkedHashSet<Integer>(updates.keySet());
        known.addAll(maneuvers.keySet());
        known.addAll(laneFrames.keySet());
        return known;
    }

    private void storeRoute(int id, Iap2NotiMsg msg) {
        if (id < 0) return;
        Update u = updates.get(id);
        if (u == null) {
            u = new Update(id, msg);
            updates.put(id, u);
        } else {
            int oldManeuver=firstIndex(u), oldLane=selectedLane(id,u);
            u.read(id,msg);
            // Consumed/current evidence cannot be reused for another maneuver or
            // selected record. Future indexed records may wait for their first use.
            if((oldManeuver>=0 && oldManeuver!=firstIndex(u)) || oldLane!=selectedLane(id,u)) {
                LinkedHashMap<Integer,LaneRecord> records=laneFrames.get(id);
                if(records!=null) { records.remove(-1); records.remove(oldLane); }
                if(turnOwner==id) invalidateAuxiliary();
            }
        }
        if (u.state > 0) u.laneSessionEnded = false;
        if(u.laneCount==0) {
            LinkedHashMap<Integer,LaneRecord> records=laneFrames.get(id);
            if(records!=null) {
                records.remove(selectedLane(id,u)); records.remove(-1);
                if(records.isEmpty()) laneFrames.remove(id);
            }
        }
        if(diagnosticReady(0)) diagnostic(0,"component="+id+" maneuver="+firstIndex(u)
                +" fields="+u.inputFields+" clear="+u.distanceCleared+" raw="+u.distNext
                +" text="+(u.nextStr.isEmpty()?0:1)+" textlen="+Math.min(u.nextStr.length(),256)
                +" unit="+u.nextUnits+" lane="+u.laneIndex+" records="+u.laneCount
                +" state="+u.state+" phase="+u.maneuverState+" phasePresent="+(u.maneuverStatePresent?1:0));
        tracePhase(id, u); // Observe state0 before turn() clears the received state.
        turn(id, u);
    }

    /** Raw iAP2 state evidence only. Apple app enums and native display enums are
     * separate contracts; no state here selects a native glyph or animates it. */
    private static final class PhaseSample {
        final int route, phase, component, maneuver, meters;
        final boolean present;
        PhaseSample(int component, Update u) {
            this.route = u.state; this.phase = u.maneuverState;
            this.present = u.maneuverStatePresent; this.component = component;
            this.maneuver = firstIndex(u); this.meters = u.distNext;
        }
        boolean sameState(PhaseSample other) {
            return other != null && route == other.route && phase == other.phase
                    && present == other.present && component == other.component
                    && maneuver == other.maneuver;
        }
    }

    private void tracePhase(int component, Update u) {
        try {
            PhaseSample current = new PhaseSample(component, u);
            boolean changed = !current.sameState(phaseSeen.get(component));
            phaseSeen.put(component, current);
            if (phaseSeen.size() > 32) phaseSeen.remove(phaseSeen.keySet().iterator().next());
            if (!changed && pendingPhase == null) return; // Distance-only deltas are silent.
            // Components can alternate in one packet. An unchanged second display
            // must not erase the first display's pending phase. Refresh its meters
            // only when this packet actually belongs to that pending component.
            if (changed || pendingPhase.component == component) pendingPhase = current;
            long now = scheduler == null ? System.nanoTime() / 1000000L : scheduler.now();
            if (phaseLogCount == phaseLogTimes.length
                    && now - phaseLogTimes[phaseLogNext] < 1000L) {
                if (changed && droppedPhases < Integer.MAX_VALUE) droppedPhases++;
                return;
            }
            phaseLogTimes[phaseLogNext] = now;
            phaseLogNext = (phaseLogNext + 1) % phaseLogTimes.length;
            if (phaseLogCount < phaseLogTimes.length) phaseLogCount++;
            PhaseSample sent = pendingPhase;
            pendingPhase = null;
            int dropped = droppedPhases;
            droppedPhases = 0;
            Dipo.log("USB_CP_TBT_PHASE route=" + sent.route + " phase=" + sent.phase
                    + " present=" + (sent.present ? 1 : 0) + " component=" + sent.component
                    + " maneuver=" + sent.maneuver + " meters=" + sent.meters + " dropped=" + dropped);
        } catch (RuntimeException ignored) { /* Observation must not interrupt guidance. */ }
    }

    /** the components a RouteGuidanceUpdate is for (item 0); null if it doesn't say */
    static int[] componentIds(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return null;
        int[] ids = null;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) return null;
            if (it.getItemParamType() == 5 && it.getItemId() == 0 && it.getItemObjCount() > 0) ids = it.getItemIntArray();
        }
        return ids;
    }

    /**
     * RouteGuidanceManeuverUpdate (DiPO e/u.d): item 0 the components, item 1 the index.
     * Stored under the index the message carries (the build's fix, by Isaac Wisdom: stock
     * wrote the index into a copy and stored every maneuver under -1, so none was found).
     */
    void maneuver(Iap2NotiMsg msg) {
        if (msg == null) return;
        int index = -1;
        int[] ids = null;
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g != null) {
            int[] found = null;
            boolean whole = true;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) {
                    whole = false;
                    break;
                }
                int id = it.getItemId();
                if (it.getItemObjCount() <= 0 || (id != 0 && id != 1)) continue;
                int[] v = it.getItemIntArray();
                if (v == null || v.length == 0) continue;
                if (id == 0) found = v;
                else index = v[0];
            }
            if (whole) ids = found;
        }
        if (index < 0) return;
        // Some apps send the component list only in the preceding route update.
        if (ids == null) {
            for (Integer id : knownComponents()) storeManeuver(id, index, msg);
        } else {
            for (int id : ids) storeManeuver(id, index, msg);
        }
    }

    private void storeManeuver(int id, int index, Iap2NotiMsg msg) {
        if (id < 0) return;
        LinkedHashMap<Integer, Maneuver> byIndex = maneuvers.get(id);
        if (byIndex == null) {
            byIndex = new LinkedHashMap<Integer, Maneuver>();
            maneuvers.put(id, byIndex);
        }
        Maneuver value = byIndex.get(index);
        if (value == null) byIndex.put(index, new Maneuver(index, id, msg));
        else value.read(index, id, msg);
        Maneuver observed=byIndex.get(index);
        if(observed!=null && diagnosticReady(1)) diagnostic(1,"component="+id+" maneuver="+index
                +" type="+observed.type+" junction="+observed.junction+" exit="+observed.exit
                +" side="+observed.side+" lane="+observed.linkedLane+" distance="+observed.distance
                +" unit="+observed.distUnits+" angles="+numbers(observed.angles));
        Update u = updates.get(id);
        if (u == null || u.list == null) return;
        for (int listed : u.list) {
            if (listed == index) {
                turn(id, u);
                return;
            }
        }
    }

    private final java.util.LinkedHashSet<String> laneReports = new java.util.LinkedHashSet<String>();
    private final java.util.LinkedHashSet<String> laneInputReports = new java.util.LinkedHashSet<String>();
    private final java.util.LinkedHashSet<String> auxiliaryReports = new java.util.LinkedHashSet<String>();

    /** Existing notify-122 only. No raw iAP2 listener or parser is enabled here. */
    void lanes(Iap2NotiMsg msg) {
        if(!outputAllowed) { laneFrames.clear(); invalidateAuxiliary(); return; }
        int[] ids=null;
        int index=-1;
        boolean hasRows=false;
        try {
            Iap2NotiMsg.Iap2ObjGroup group=msg==null?null:msg.getItemGroup();
            if(group==null) return;
            if(group.getItemCnt()<0 || group.getItemCnt()>32) throw new IllegalArgumentException("lane fields");
            ids=componentIds(msg);
            if(ids!=null && ids.length>MAX_LANE_COMPONENTS) throw new IllegalArgumentException("components");
            Iap2NotiMsg.Iap2ObjGroup[] groups=null;
            for(int k=0;k<group.getItemCnt();k++) {
                Iap2NotiMsg.Iap2ObjItem item=group.getObjItem(k);
                if(item==null) continue;
                if(item.getItemId()==1 && item.getItemParamType()==5) {
                    int[] values=item.getItemIntArray();
                    if(values!=null && values.length==1 && values[0]>=0) index=values[0];
                    else throw new IllegalArgumentException("guidance index");
                }
                if(item.getItemId()==2) {
                    if(hasRows || item.getItemParamType()!=13) throw new IllegalArgumentException("lane groups");
                    hasRows=true; groups=item.getItemGroupArray();
                }
            }
            if(!hasRows) return; // A metadata-only delta is not an empty record.
            CpLaneCallback.Rows rows=CpLaneCallback.read(groups);
            if(ids==null) {
                for(Integer id:knownComponents()) storeLanes(id,index,rows);
            } else for(int id:ids) if(id>=0) storeLanes(id,index,rows);
            reportLanes(groups,rows.frame);
        } catch(Throwable failed) {
            auxiliaryFailure("callback");
            // Malformed lane input cannot erase route/maneuver state or abort a
            // later core update. Discard affected lane evidence rather than retain
            // a stale recommendation/picture after an unusable explicit packet.
            if(ids==null) laneFrames.clear();
            else for(int id:ids) laneFrames.remove(id);
            if(displayed) renderAuxiliary(turnOwner,updates.get(turnOwner));
        }
    }

    private static int firstIndex(Update u) {
        return u == null || u.list == null || u.list.length == 0 ? -1 : u.list[0];
    }

    private int selectedLane(int id, Update u) {
        // Route item 16 is the current LaneGuidanceIndex. Item 17 is the total
        // number of guidance records, not the number of physical road lanes.
        if (u != null && u.laneIndex >= 0) return u.laneIndex;
        LinkedHashMap<Integer, Maneuver> byIndex = maneuvers.get(id);
        Maneuver current = byIndex == null ? null : byIndex.get(firstIndex(u));
        return current == null ? -1 : current.linkedLane;
    }

    long laneGeneration() { return laneInputGeneration; }
    boolean acceptsRawLanes() { return outputAllowed && auxiliaryAllowed; }

    /** Handler-owned normalized fallback. A valid OEM record, including an empty
     * one, wins over raw packets for the same component/index until invalidated. */
    void fallbackLanes(CpLaneDecoder.Info info,long generation) {
        if(info==null || generation!=laneInputGeneration || !acceptsRawLanes() || info.component<0 || info.index<0) return;
        try { storeLanes(info.component,info.index,CpLaneCallback.wireRows(info.lanes),false); }
        catch(Throwable failed) { auxiliaryFailure("raw-normalize"); }
    }

    private void storeLanes(int id,int index,CpLaneCallback.Rows rows) { storeLanes(id,index,rows,true); }

    private void storeLanes(int id,int index,CpLaneCallback.Rows rows,boolean oem) {
        Update u=updates.get(id);
        if(u!=null && (u.laneSessionEnded || (u.laneCount==0 && (index<0 || index==selectedLane(id,u))))) return;
        LinkedHashMap<Integer,LaneRecord> records=laneFrames.get(id);
        if(records==null) {
            while(laneFrames.size()>=MAX_LANE_COMPONENTS) {
                Integer evict=null;
                for(Integer old:laneFrames.keySet()) if(old!=turnOwner) { evict=old; break; }
                if(evict==null) return;
                laneFrames.remove(evict);
            }
            records=new LinkedHashMap<Integer,LaneRecord>(); laneFrames.put(id,records);
        }
        int selected=selectedLane(id,u);
        if(index<0 && selected>=0) index=selected;
        LaneRecord prior=records.get(index);
        long priorAge=prior==null ? -1L : laneNow()-prior.received;
        boolean priorFresh=prior!=null && priorAge>=0 && priorAge<=MAX_LANE_AGE_MS;
        if(!oem && priorFresh && prior.oem && prior.rows.exact!=null) return;
        if(oem && rows.exact==null && priorFresh && !prior.oem && prior.rows.exact!=null) return;
        LaneRecord record=new LaneRecord(rows,laneNow(),oem);
        if(u!=null && index==selected && firstIndex(u)>=0) {
            record.claimed=true; record.maneuver=firstIndex(u);
        }
        records.remove(index); records.put(index,record);
        if(diagnosticReady(2)) {
            CpLaneDecoder.Lane[] seen=rows.observed;
            int[] statuses=new int[seen.length],highlights=new int[seen.length],indexes=new int[seen.length],counts=new int[seen.length];
            int count=0;for(CpLaneDecoder.Lane l:seen)count+=l.angles.length;
            int[] angles=new int[Math.min(count,128)];int at=0;
            for(int i=0;i<seen.length;i++) {
                CpLaneDecoder.Lane l=seen[i];indexes[i]=l.index;statuses[i]=l.status;highlights[i]=l.highlight;counts[i]=l.angles.length;
                for(int a:l.angles)if(at<angles.length)angles[at++]=a;
            }
            diagnostic(2,"component="+id+" index="+index+" source="+(oem?1:0)
                    +" exact="+(rows.exact==null?0:1)+" count="+seen.length
                    +" indexes="+numbers(indexes)+" states="+numbers(statuses)+" highlights="+numbers(highlights)
                    +" angleCounts="+numbers(counts)+" angles="+numbers(angles)+" frame="+frameNumbers(rows.frame));
        }
        if(laneInputReports.size()<12) {
            String detail="source="+(oem?"oem":"raw")+" component="+id+" index="+index
                    +" lanes="+(rows.frame[1]&255)+" exact="+(rows.exact==null?0:1);
            if(laneInputReports.add(detail)) {
                try { Dipo.log("USB_CP_TBT_LANE_INPUT "+detail); } catch(Throwable ignored) {}
            }
        }
        while(records.size()>MAX_LANE_FRAMES) {
            for(Integer key:new java.util.ArrayList<Integer>(records.keySet())) {
                if(key!=selected) { records.remove(key); break; }
            }
        }
        renderAuxiliary(id,u);
    }

    private LaneRecord selectedRecord(int id,Update u) {
        if(u==null || u.laneCount==0 || u.laneSessionEnded) return null;
        LinkedHashMap<Integer,LaneRecord> records=laneFrames.get(id);
        int selected=selectedLane(id,u);
        LaneRecord record=records==null ? null : records.get(selected);
        if(record==null) return null;
        long age=laneNow()-record.received;
        if(age<0 || age>MAX_LANE_AGE_MS || (record.claimed && record.maneuver!=firstIndex(u))) {
            records.remove(selected); return null;
        }
        if(!record.claimed && firstIndex(u)>=0) { record.claimed=true; record.maneuver=firstIndex(u); }
        return record;
    }

    private static byte[] emptyLanes() { byte[] frame=new byte[81]; frame[0]=(byte)0xf1; return frame; }
    private static int[] emptyPattern() { return new int[]{65535,65535,65535,0,0}; }

    private void invalidateAuxiliary() {
        auxiliaryEpoch++;
        auxiliaryComponent=auxiliaryManeuver=auxiliaryLane=-1;
        if(auxiliary!=null) auxiliary.invalidate(auxiliaryEpoch);
    }

    private void clearAuxiliary() {
        if(!auxiliaryStarted) return;
        if(auxiliaryComponent>=0) invalidateAuxiliary();
        if(outputAllowed && auxiliary!=null) auxiliary.submit(emptyLanes(),emptyPattern(),auxiliaryEpoch);
    }

    /** Called only after basic main/HUD (or by a later lane-only callback). All
     * optional writes are complete snapshots on a bounded independent worker. */
    private void renderAuxiliary(int id,Update u) {
        if(auxiliary==null || !outputAllowed || !displayed || turnOwner!=id || u==null) return;
        int maneuver=firstIndex(u), selected=selectedLane(id,u);
        if(auxiliaryComponent!=id || auxiliaryManeuver!=maneuver || auxiliaryLane!=selected) {
            invalidateAuxiliary();
            auxiliaryComponent=id; auxiliaryManeuver=maneuver; auxiliaryLane=selected;
        }
        LaneRecord record=null;
        byte[] lane=emptyLanes();
        try { record=selectedRecord(id,u); if(record!=null) lane=record.rows.frame; }
        catch(Throwable failed) { auxiliaryFailure("lane-select"); }
        int[] pattern=emptyPattern();
        try {
            LinkedHashMap<Integer,Maneuver> known=maneuvers.get(id);
            Maneuver current=known==null ? null : known.get(maneuver);
            // Reuse the already accepted core distance. Do not add a vehicle
            // property/Binder lookup to the optional-selection path.
            double meters=lastPrimaryValid && lastPrimaryComponent==id && lastPrimaryManeuver==maneuver
                    ? new Distance(lastPrimaryValue,lastPrimaryUnit).guideMeters(u.distNext) : -1d;
            if(current!=null && u.state!=2 && u.state!=6 && meters>=0 && meters<=457) {
                // Lane counts do not establish actual ramp curvature. The
                // heuristic CpJunctions PE14 path stays unused until reviewed
                // picture/input mappings are supplied in a later release.
                // Only the existing native pattern with complete explicit road
                // angles may be used; otherwise send the established clear.
                int icon=current.primaryIcon(u.state);
                int[] nativeTurn=standardTurnAtWindow(current,icon,meters,u.state,457);
                int type=nativeTurn==null ? icon : nativeTurn[0];
                int[] fallback=type>=13 && type<=18
                        ? NativeJunction.pattern(current.type,current.junction,current.exit,current.angles) : null;
                if(fallback!=null) pattern=fallback;
            }
        } catch(Throwable failed) { auxiliaryFailure("junction-select"); }
        if(auxiliaryReports.size()<16) {
            String detail="component="+id+" maneuver="+maneuver+" index="+selected
                    +" source="+(record==null?"none":record.oem?"oem":"raw")
                    +" exact="+(record!=null && record.rows.exact!=null?1:0)+" guide="+pattern[0];
            if(auxiliaryReports.add(detail)) {
                try { Dipo.log("USB_CP_TBT_AUX_SELECT "+detail); } catch(Throwable ignored) {}
            }
        }
        if(diagnosticReady(3)) diagnostic(3,"component="+id+" maneuver="+maneuver+" index="+selected
                +" source="+(record==null?-1:record.oem?1:0)+" exact="+(record!=null && record.rows.exact!=null?1:0)
                +" laneAge="+(record==null?-1:Math.max(0,laneNow()-record.received))
                +" templatePolicy=0 guide="+pattern[0]+" arrow="+pattern[1]+" frame="+frameNumbers(lane));
        try { auxiliaryStarted=true; auxiliary.submit(lane,pattern,auxiliaryEpoch); }
        catch(Throwable failed) { auxiliaryFailure("submit"); }
    }

    // A small, bounded sample lets a later USB history distinguish an input lane
    // with no angles from unused frame padding. No road names or location are logged.
    private void reportLanes(Iap2NotiMsg.Iap2ObjGroup[] lanes, byte[] frame) {
        if (laneReports.size() >= 12) return;
        StringBuilder report = new StringBuilder("input=").append(lanes.length)
                .append(" output=").append(frame[1] & 255);
        for (int i = 0; i < lanes.length && i < 8; i++) {
            report.append(" lane").append(i).append('=');
            Iap2NotiMsg.Iap2ObjGroup lane = lanes[i];
            if (lane == null) { report.append("unknown"); continue; }
            for (int j = 0; j < lane.getItemCnt(); j++) {
                Iap2NotiMsg.Iap2ObjItem item = lane.getObjItem(j);
                if (item == null || item.getItemId() < 1 || item.getItemId() > 3) continue;
                int type = item.getItemParamType();
                if (type != 5 && type != 8) continue;
                int[] values = item.getItemIntArray();
                if (values == null) continue;
                report.append(item.getItemId()).append(':').append('[');
                for (int k = 0; k < values.length && k < 4; k++) {
                    if (k > 0) report.append(',');
                    report.append(values[k]);
                }
                if (values.length > 4) report.append(",...");
                report.append(']');
            }
        }
        String detail = report.toString();
        if(laneReports.add(detail)) {
            try { Dipo.log("USB_CP_TBT_LANES "+detail); } catch(Throwable ignored) {}
        }
    }

    /** Normalized primary/secondary union and phone-provided status; three
     * independent 2-bit style fields use the verified glyph combinations. */
    static int[] laneOne(Iap2NotiMsg.Iap2ObjGroup group) {
        return CpLaneCallback.values(CpLaneCallback.row(group));
    }

    /** a lane angle (degrees, right positive) as the cluster's arrow: 1 straight, 2-4 right, 5 back, 6-8 left */
    static int laneIcon(int a) {
        // Accept signed degrees and the reference build's unsigned full-circle
        // representation. Out-of-range/sentinel values are not U-turn arrows.
        if (a < -360 || a > 360) return 0;
        if (a > 180) a -= 360;
        else if (a < -180) a += 360;
        if (a >= 0) {
            if (a <= 22) return 1;
            if (a <= 67) return 2;
            if (a <= 112) return 3;
            return a <= 157 ? 4 : 5;
        }
        a = -a;
        if (a <= 22) return 1;
        if (a <= 67) return 8;
        if (a <= 112) return 7;
        return a <= 157 ? 6 : 5;
    }

    /** the iAP2 session ended (DiPO e/u.disconnected) */
    void disconnected() {
        stop();
        source = null;
    }

    /** route guidance stopped (DiPO e/u.nS: the car's navigation took turn-by-turn back, or state 0) */
    void stop() {
        reset();
        clearTurn();
        clearTrip();
        lastDistanceAvailable = null;
    }

    private void clearTurn() {
        lastPrimaryComponent = lastPrimaryManeuver = -1;
        if (!outputAllowed) {

            displayed = false;
            turnOwner = -1;
            lastPrimaryIcon = lastHudUnit = -1;
            lastRoad = hudRoad = null;
            hudHasDistance = false;
            invalidateAuxiliary();
            return;
        }
        if (!displayed) { clearAuxiliary(); return; }
        displayed = false;
        turnOwner = -1;
        lastPrimaryIcon = lastHudUnit = -1;
        lastRoad = null;
        cluster.tbtInfo(0, 0, 0f, 0f, -1, 0, 0, 0, -1);
        cluster.crossroadName("");
        hudBlank();
        hudHasDistance = false;
        clearAuxiliary();
    }

    private void clearTrip() {
        cancelTripFlush();
        if (outputAllowed && tripOwner >= 0) {
            cluster.destinationName(0, "");
            cluster.tbtSubInfo(0f, -1, 0, 0, 0, 0, 0, 0f, -1, 0);
        }
        lastTrip = null;
        lastDestination = null;
        tripOwner = -1;
    }

    /** forget every component's state and maneuvers (DiPO e/u.nT) */
    void reset() {
        laneInputGeneration++;
        maneuvers.clear();
        updates.clear();
        laneFrames.clear();
        phaseSeen.clear();
        pendingPhase = null;
        // Keep the rolling rate budget across source changes and reconnects.
    }

    /** SourceName (item 19) changed: clear the old app's leftovers and have the phone resend */
    void sourceCheck(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null || it.getItemId() != 19 || it.getItemParamType() != 10 || it.getItemObjCount() <= 0) continue;
            String[] v = it.getItemStringArray();
            String s = v == null || v.length == 0 ? null : v[0];
            if (s == null || s.length() == 0) continue;
            String was = source;
            source = s;
            if (was == null || was.equals(s)) return;
            Dipo.log("USB_CP_TBT_SOURCE_CHANGED");
            stop();
            if (restart != null) restart.run();
            return;
        }
    }

    /** one component's update to the cluster (DiPO e/u.a(Integer, Object), with the build's changes) */
    void turn(Integer id, Update u) {
        if (id < 0 || u == null) return;
        if (u.state == 0) {
            laneInputGeneration++;
            maneuvers.remove(id);
            laneFrames.remove(id);
            u.clear();
            u.laneSessionEnded = true;
            if (turnOwner == id) clearTurn();
            if (tripOwner == id) clearTrip();
            return;
        }
        if (!outputAllowed) {
            clearTurn();
            clearTrip();
            laneFrames.clear();
            invalidateAuxiliary();
            return;
        }
        if (u.state < 0) return;
        // Route totals belong to the trip. A missing next maneuver must not clear them.
        int ownerUnit = Car.distanceUnit(context);
        sendTrip(id, u, ownerUnit);
        if (u.state == 6 && !u.destination.isEmpty() && !u.destination.equals(lastDestination)) {
            cluster.destinationName(0, u.destination);
            lastDestination = u.destination;
            tripOwner = id;
        }
        int[] list = u.list;
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        Maneuver x = m == null || list == null || list.length == 0 ? null : m.get(list[0]);
        if (x == null) {
            if (turnOwner == id) clearTurn();
            return;
        }
        Distance distance = Distance.resolve(u.distNext, u.nextStr, u.nextUnits, ownerUnit);
        boolean hasDistance = distance != null;
        if (lastDistanceAvailable == null || lastDistanceAvailable.booleanValue() != hasDistance) {
            Dipo.log("USB_CP_TBT_DISTANCE raw=" + (u.distNext >= 0) + " text=" + !u.nextStr.isEmpty()
                    + " displayUnit=" + u.nextUnits + " usable=" + (distance != null));
            lastDistanceAvailable = hasDistance;
        }
        if(!hasDistance) {
            if(diagnosticReady(4)) diagnostic(4,"component="+id+" maneuver="+firstIndex(u)
                    +" fields="+u.inputFields+" clear="+u.distanceCleared+" raw="+u.distNext
                    +" text="+(u.nextStr.isEmpty()?0:1)+" unit="+u.nextUnits+" state="+u.state);
            // The existing invalid flag still displays 0m on this cluster. No
            // verified numeric blank sentinel exists. Use the established whole
            // maneuver clear contract; keep trip totals until actual distance
            // arrives. A genuine raw/formatted zero remains a valid maneuver.
            if(turnOwner==id) clearTurn();
            return;
        }
        int icon = x.primaryIcon(u.state);
        if ((icon == 0 || icon == 64) && missingManeuvers.size() < 12) {
            String fields = "type=" + x.type + " side=" + x.side + " junction=" + x.junction
                    + " exit=" + (validAngle(x.exit) ? Integer.toString(x.exit) : "absent");
            if (missingManeuvers.add(fields)) Dipo.log("USB_CP_TBT_MANEUVER_DETAIL " + fields);
        }
        if (icon == 0) {
            if (turnOwner == id) clearTurn();
            return;
        }
        if (!displayed) Dipo.log("USB_CP_TBT_DISPLAY first_turn icon=" + icon);
        displayed = true;
        turnOwner = id;
        String road = x.displayRoad();
        if (!road.equals(lastRoad)) {
            cluster.crossroadName(road);
            lastRoad = road;
        }
        float value = distance == null ? 0f : distance.value;
        int unit = distance == null ? Cluster.UNIT_M : distance.unit;
        double guideMeters = distance == null ? -1d : distance.guideMeters(u.distNext);
        int mainIcon = icon, mainBar = 0;
        float mainDirection = 0;
        if (standardTurns && (hasDistance || u.state == 2)) {
            // Some phones send only a formatted distance delta. It replaces stale
            // raw meters in Update.read and can still drive the guide progress.
            // Preserve its units/precision: do not truncate 300.1 m into the window.
            int[] standard = standardTurn(x, icon, guideMeters, u.state);
            if (standard != null) { mainIcon = standard[0]; mainDirection = standard[1]; mainBar = standard[2]; }
        }
        // A new maneuver may have the same numeric tuple. It still owns a new
        // update; do not let deduplication retain the preceding maneuver's frame.
        int maneuverIndex = firstIndex(u);
        if (id != lastPrimaryComponent || maneuverIndex != lastPrimaryManeuver) {
            lastPrimaryIcon = -1;
            lastPrimaryComponent = id;
            lastPrimaryManeuver = maneuverIndex;
        }
        // Korean Leafia r8.2's formatted main frame distinguishes projection
        // glyphs (no native side street) from native turn geometry. This field
        // is independent of the HUD F2 list, which retains projection glyphs.
        int mainSideStreet = standardTurns && mainIcon >= 64 ? 65535 : 0;
        // Item 10 describes the other roads; item 11 separately selects the exit.
        // Only attach explicit, compatible ordinary-junction geometry to TURN.
        if (standardTurns && mainIcon == 13 && x.junction == 0 && x.angles != null
                && validAngle(x.exit) && compatibleExit(x, mainDirection)) {
            NativeSideStreet.Result geometry = NativeSideStreet.map(x.angles, x.exit);
            if (geometry != null) {
                mainSideStreet = geometry.mask;
                mainDirection = geometry.directionDegrees;
            }
        }
        if (mainIcon != lastPrimaryIcon || mainDirection != lastPrimaryDirection || mainBar != lastPrimaryBar
                || mainSideStreet != lastPrimarySide
                || unit != lastPrimaryUnit || value != lastPrimaryValue
                || hasDistance != lastPrimaryValid) {
            cluster.tbtInfo(mainIcon, mainSideStreet, (float) mainDirection, value, hasDistance ? 0 : -1, 0, unit, mainBar, 0);
            tracePrimary(u.distNext >= 0 ? "raw" : hasDistance ? "display" : "none",
                    guideMeters, mainIcon, mainDirection, mainBar, value, unit, hasDistance);
            lastPrimaryIcon = mainIcon;
            lastPrimaryDirection = mainDirection;
            lastPrimaryBar = mainBar;
            lastPrimarySide = mainSideStreet;
            lastPrimaryValue = value;
            lastPrimaryUnit = unit;
            lastPrimaryValid = hasDistance;
        }
        sendHud(icon, id, u);
        if (hasDistance || hudHasDistance) sendHudDist(value, unit);
        hudHasDistance = hasDistance;
        sendAhead(id, u);
        renderAuxiliary(id, u);
    }

    /** Immediate, nonblocking gate for stock ownership takeover before a queued
     * TBT-handler stop runs. An IPC already inside the framework cannot be canceled. */
    void revokeAuxiliary() { auxiliaryAllowed=false; }

    void setOutputAllowed(boolean allowed) {
        if(allowed && !auxiliaryAllowed) {
            invalidateAuxiliary(); // Never revive a queued old-owner clear.
            auxiliaryAllowed=true;
        }
        if(!allowed) auxiliaryAllowed=false;
        outputAllowed = allowed;
        if (!allowed) { laneInputGeneration++; laneFrames.clear(); invalidateAuxiliary(); clearTurn(); clearTrip(); }
    }

    private static boolean compatibleExit(Maneuver x, float nativeDirection) {
        // A typed right turn with a zero/left exit keeps its ordinary fallback;
        // stale or contradictory geometry must not rotate that turn to straight.
        int exit = ((x.exit % 360) + 360) % 360;
        return Math.abs(exit - nativeDirection) < 0.01f;
    }

    /** Finite numeric samples of frames actually written, at most once a second.
     * This observes the phone's distance, not vehicle position or a synthetic path.
     * Millimetre-scaled integers avoid locale/exponent text in the USB allowlist. */
    private void tracePrimary(String source, double meters, int type, float direction, int bar,
            float value, int unit, boolean valid) {
        long now = scheduler == null ? System.nanoTime() / 1000000L : scheduler.now();
        if (primaryTraced && now - lastPrimaryTraceAt < 1000L) return;
        primaryTraced = true;
        lastPrimaryTraceAt = now;
        Dipo.log("USB_CP_TBT_RENDER source=" + source + " metersMilli="
                + (meters < 0d ? -1L : Math.round(meters * 1000d))
                + " type=" + type + " direction=" + direction + " bar=" + bar
                + " distanceMilli=" + Math.round((double) value * 1000d)
                + " unit=" + unit + " valid=" + (valid ? 1 : 0));
    }

    /** Optional main-display format from HMG-API PR75's US NEEV evidence.
     * iAP2 types are translated separately from Android Auto's unrelated enum.
     * KOR appearance is unverified; unsupported input keeps its existing icon.
     * The HUD F2 list always retains the projection icons above. */
    static int[] standardTurn(Maneuver x, int icon, int meters) {
        return standardTurn(x, icon, meters, 1);
    }

    static int[] standardTurn(Maneuver x, int icon, int meters, int state) {
        return standardTurn(x, icon, (double) meters, state);
    }

    static int[] standardTurn(Maneuver x, int icon, double meters, int state) {
        return standardTurnAtWindow(x,icon,meters,state,300);
    }

    private static int[] standardTurnAtWindow(Maneuver x,int icon,double meters,int state,int window) {
        if (x != null && state == 2) return new int[] { 3, 0, 0 }; // explicit arrived state, as r8.2
        if (x == null || Double.isNaN(meters) || meters < 0 || meters > window) return null;
        int direction = -1, type = 13;
        if (x.junction == 1 && (x.type == 1 || x.type == 2 || x.type == 7 || x.type == 19
                || (x.type >= 28 && x.type <= 46)) && (x.side == 0 || x.side == 1)) {
            int angle = x.type == 19 && !validAngle(x.exit) ? 180 : x.exit;
            if (!validAngle(angle)) return null;
            type = x.side == 0 ? 32 : 33;
            direction = (angle + 360) % 360;
        } else if (x.junction == 0) {
            switch (x.type) {
                case 1: case 20: direction=standardDirection(x,270,-1,0); break;
                case 2: case 21: direction=standardDirection(x,90,1,0); break;
                case 3: if (!validAngle(x.exit) || x.exit==0) direction=0; break;
                case 0:
                    direction=explicitIntersectionDirection(x);
                    break;
                case 5:
                    direction=explicitIntersectionDirection(x);
                    if (direction < 0 && (!validAngle(x.exit) || x.exit==0) && !x.textUTurn
                            && (!validAngle(x.textAngle) || x.textAngle==0)) direction=0;
                    break;
                case 13: direction=standardDirection(x,315,-1,0); break;
                case 14: direction=standardDirection(x,45,1,0); break;
                case 47: direction=standardDirection(x,225,-1,2); break;
                case 48: direction=standardDirection(x,135,1,2); break;
                case 49: direction=standardDirection(x,315,-1,1); break;
                case 50: direction=standardDirection(x,45,1,1); break;
                case 4: case 18: case 26:
                    if (x.side != 0 && x.side != 1) break;
                    if (validAngle(x.exit) && x.exit!=0 && Math.abs(x.exit)<158) break;
                    type=31; direction=x.side==0?270:90; break;
                case 8:
                    if (!validAngle(x.exit) || x.exit==0 || Math.abs(x.exit)==180) break;
                    type=x.exit>0?17:18; direction=(x.exit+360)%360;
                    break;
                case 22: type=18; direction=standardDirection(x,270,-1,0); break;
                case 23: type=17; direction=standardDirection(x,90,1,0); break;
                default: break;
            }
        }
        if (direction < 0) return null;
        // Native SetCaseTurnPoint emits max(1, (100-progress)/10) nibbles.
        // The owner verified ordinary stock TBT -> AR at300m in this car.
        // Main CP/AA guidance shares that window; PE14 uses its own window.
        // This does not infer dynamic thresholds for other road categories.
        // Exact start may send100.
        // No warning/trap frame is repurposed as a cursor-position channel.
        // This controls the documented bar field; a passed-turn animation
        // cannot be inferred from the numeric range accepted by the API.
        int bar = Math.max(1, Math.min(10, (int) (meters * 10d / window))) * 10;
        return new int[] { type, direction, bar };
    }

    /** Neutral follow-road guidance can still identify a real crossing. Native
     * type13 carries the selected bearing plus explicit other arms, not only a
     * left/right glyph. Require the same complete intersection evidence as the
     * basic clock-direction icon; never promote text-only directions or invent
     * arms from lane recommendations. The final side-street writer applies the
     * native 22.5-degree bins (KOR stock capture includes direction67.5/mask1033).
     */
    private static int explicitIntersectionDirection(Maneuver x) {
        if (x.junction != 0 || x.textUTurn || x.angles == null || x.angles.length < 2
                || !validAngle(x.exit) || NativeSideStreet.map(x.angles, x.exit) == null) return -1;
        return (x.exit + 360) % 360;
    }

    private static int standardDirection(Maneuver x, int fallback, int sign, int kind) {
        // Leafia r8.2 directional() uses the typed turn's bearing when the
        // selected angle is absent or zero. Keep nonzero contradictions below.
        if (!validAngle(x.exit) || x.exit==0) return fallback;
        int angle=x.exit, magnitude=Math.abs(angle);
        if (magnitude>=180 || (angle<0?-1:1)!=sign) return -1;
        if ((kind==1 && magnitude>67) || (kind==2 && magnitude<113)) return -1;
        return (angle+360)%360;
    }

    /**
     * The cluster icon for a maneuver: iAP2 ManeuverType, driving side (0 right-hand
     * traffic, 1 left), junction type (0 intersection, 1 roundabout) and exit angle, as
     * the turn-by-turn build maps them (0: nothing to show).
     */
    static int icon(int type, int side, int junction, int exit, int state) {
        if (type == 19 || (junction == 1 && type == 4)) return 102; // U-turn at a roundabout, even without geometry
        if (type == 4 || type == 18 || type == 26) return side == 1 ? 73 : 72;
        if (isRoundaboutType(type) || (junction == 1 && (type == 1 || type == 2 || type == 3))) {
            return validAngle(exit) ? roundaboutIcon(exit) : 64;
        }
        switch (type) {
            case 1: case 20: // left / left at end of road
                if (exit == -45) return 145;
                if (validAngle(exit) && exit < -110) return 69;
                if (validAngle(exit) && exit < 0 && exit > -70) return 71;
                return 70;
            case 2: case 21: // right / right at end of road
                if (exit == 45) return 144;
                if (validAngle(exit) && exit > 110) return 68;
                if (validAngle(exit) && exit > 0 && exit < 70) return 66;
                return 67;
            case 3: return 65;
            case 13: case 49: return 71; // keep/slight left
            case 14: case 50: return 66; // keep/slight right
            case 47: return 69;
            case 48: return 68;
            case 5:
                return side == 0 && junction == 0 && exit == 0 ? 172 : 0;
            // Gen5W GPM.setRampDisplayType: OFF_RAMP unknown/right/left =
            // 86/81/82; ON_RAMP unknown/right/left = 172/173/174.
            // A selected bearing gives the branch side, not the driving side.
            case 8:
                if (side != 0 || junction != 0) return 0;
                return rampSide(exit) < 0 ? 82 : rampSide(exit) > 0 ? 81 : 86;
            case 9:
                if (side != 0 || junction != 0) return 0;
                return rampSide(exit) < 0 ? 174 : rampSide(exit) > 0 ? 173 : 172;
            case 12: {   // arrive; state 2 (arrived): 3
                int i = side == 0 && junction == 0 ? 112 : 0;
                return state == 2 ? 3 : i;
            }
            case 15: return side == 0 && junction == 0 && exit == 0 ? 176 : 0;
            case 22: return side == 0 && junction == 0 ? 82 : 0;
            case 23: return side == 0 && junction == 0 ? 81 : 0;
            case 24: return side == 0 && junction == 0 && exit == 0 ? 114 : 0;
            case 25: return side == 0 && junction == 0 && exit == 0 ? 113 : 0;
            case 51:
                if (side != 0 || junction != 0) return 0;
                // ChangeHighway has no verified generic glyph. Only choose its
                // existing Left/Right glyph when the phone supplies a bearing.
                return rampSide(exit) < 0 ? 174 : rampSide(exit) > 0 ? 173 : 0;
            case 52: return side == 0 && junction == 0 ? 174 : 0;
            case 53: return side == 0 && junction == 0 ? 173 : 0;
            default: return 0;
        }
    }

    static final int NO_ANGLE = Integer.MIN_VALUE;
    static boolean validAngle(int a) { return a >= -180 && a <= 180; }
    /** r8.2's nativeTurn accepts a full signed revolution before normalizing it.
     * Keep only the selected road (item 11): other junction arms are not exits.
     * In particular 270 is the same left bearing as -90, not missing geometry. */
    static int selectedAngle(int raw) {
        if (raw < -360 || raw > 360) return NO_ANGLE;
        return raw > 180 ? raw - 360 : raw < -180 ? raw + 360 : raw;
    }
    private static int rampSide(int angle) {
        if (!validAngle(angle) || angle == -180 || angle == 180) return 0;
        return angle < 0 ? -1 : angle > 0 ? 1 : 0;
    }
    static boolean isRoundaboutType(int type) {
        return type == 6 || type == 7 || type == 19 || (type >= 28 && type <= 46);
    }
    private static final int[] ROUNDABOUT_ICONS = {96, 97, 99, 100, 102, 103, 105, 106};
    private static final int[] INTERSECTION_ICONS = {65, 66, 67, 68, 64, 69, 70, 71};
    private static int sector(int degrees) { return (((degrees + 360) % 360) + 22) / 45 % 8; }
    private static int roundaboutIcon(int degrees) {
        // Known Gen5W glyphs for the closest sector. Preserve the two more-specific
        // legacy glyphs observed for -30/-120; exact clock text uses the real angle.
        if (degrees == -30) return 107;
        if (degrees == -120) return 104;
        return ROUNDABOUT_ICONS[sector(degrees)];
    }

    /** The small next-turn field has fewer glyphs than the current-turn field. */
    static int aheadIcon(Maneuver x) {
        int value = x.primaryIcon(1);
        if (value == 144) return 66;
        if (value == 145) return 71;
        return value >= 65 && value <= 73 ? value : 64;
    }

    /** the HUD's list frame (0xF2): count, this icon, the next turn's icon, 0, road after this turn (UTF-16LE) */
    void sendHud(int type, Integer id, Update u) {
        if (type < 64) return;
        int next = 0;
        String road = "";
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        int[] list = u == null ? null : u.list;
        if (m != null && list != null && list.length > 0) {
            Maneuver x = m.get(list[0]);
            if (x != null) road = x.displayRoad();
            if (list.length > 1) {
                Maneuver y = m.get(list[1]);
                if (y != null && y.distance > 0) next = aheadIcon(y);
            }
        }
        if (hudType == type && hudNext == next && hudRoad != null && hudRoad.equals(road)) return;
        byte[] r;
        try {
            r = road.getBytes("UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        int n = Math.min(r.length, 78);
        if (n < r.length && n >= 2 && Character.isHighSurrogate(road.charAt(n / 2 - 1))) n -= 2;
        byte[] f = new byte[n + 5];
        f[0] = (byte) 0xF2;
        f[1] = (byte) (next != 0 ? 2 : 1);
        f[2] = (byte) type;
        f[3] = (byte) next;
        System.arraycopy(r, 0, f, 5, n);
        Dipo.log("CarPlay: hud list type " + type + " next " + next);
        cluster.turnListNew(f);
        hudType = type;
        hudNext = next;
        hudRoad = road;
    }

    /** the HUD's distance (updateTBTSubInfo2C: unit, tenths for km/mi, whole up to 4095) */
    void sendHudDist(float f, int unit) {
        if (f < 0f || unit < 0 || unit > 4) return;
        boolean decimal = unit == Cluster.UNIT_KM || unit == Cluster.UNIT_MI;
        int scaled = Math.min(Math.round(f * (decimal ? 10f : 1f)), decimal ? 40959 : 4095);
        if (unit == lastHudUnit && scaled == lastHudScaled) return;
        lastHudUnit = unit;
        lastHudScaled = scaled;
        int whole = decimal ? scaled / 10 : scaled;
        int tenths = decimal ? scaled % 10 : 0;
        cluster.tbtSubInfo2C(unit, tenths, whole, 0, 0, 0, 0, 0, 0);
    }

    /** HUD list and extras cleared (the build's sendHudBlank) */
    void hudBlank() {
        cluster.tbtSubInfo2C(0, 0, 0, 0, 0, 0, 0, 0, 0);
        hudType = -1;
        hudNext = 0;
        hudRoad = null;
        byte[] empty = { (byte) 0xF2 };
        cluster.turnListNew(empty);
        cluster.turnListNew(empty);
    }

    /**
     * Remaining distance and arrival time (updateTBTSubInfo). Distance in the owner's
     * units (Settings), else the phone's. Arrival: a clock time (seconds since 2001 or
     * 1970) or, failing that, now + the time remaining (up to a week).
     */
    void sendTrip(int id, Update u, int ownerUnit) {
        Distance distance = Distance.resolve(u.distLeft, u.distLeftStr, u.distLeftUnits, ownerUnit);
        int hour = -1, minute = -1, timeType = -1, timeFormat;
        Calendar cal = null;
        if (u.eta >= 500000000L && u.eta < 10000000000L) {
            long eta = u.eta < 1500000000L ? u.eta + 978307200L : u.eta;
            cal = Calendar.getInstance();
            cal.setTimeInMillis(eta * 1000L);
        } else if (u.arrivalFromDuration > 0) {
            cal = Calendar.getInstance();
            cal.setTimeInMillis(u.arrivalFromDuration);
        }
        if (cal != null) {
            hour = cal.get(Calendar.HOUR_OF_DAY);
            minute = cal.get(Calendar.MINUTE);
            timeType = 1;
            timeFormat = context == null || DateFormat.is24HourFormat(context) ? 2 : 1;
        } else {
            timeFormat = -1;
        }
        if (distance == null && timeType < 0) return;
        int error = distance == null ? -1 : 0;
        float value = distance == null ? 0f : distance.value;
        int unit = distance == null ? Cluster.UNIT_M : distance.unit;
        offerTrip(id, new TripFrame(value, error, unit, hour, minute, timeType, timeFormat));
    }

    /** Cluster-visible values only, so repeated 120/121 packets cannot redraw unchanged trip data. */
    static final class TripFrame {
        final float value;
        final int error, unit, hour, minute, type, format;
        TripFrame(float value, int error, int unit, int hour, int minute, int type, int format) {
            this.value=value; this.error=error; this.unit=unit; this.hour=hour;
            this.minute=minute; this.type=type; this.format=format;
        }
        boolean matches(TripFrame other) {
            return other != null && value == other.value && error == other.error
                    && unit == other.unit && hour == other.hour && minute == other.minute
                    && type == other.type && format == other.format;
        }
    }

    private void cancelTripFlush() {
        if (tripFlush != null) scheduler.cancel(tripFlush);
        tripFlush = null;
        pendingTrip = null;
        scheduledOwner = null;
    }

    private void writeTrip(int id, TripFrame f) {
        cluster.tbtSubInfo(f.value, f.error, f.unit, f.hour, f.minute, f.type, f.format, 0f, -1, 0);
        tripOwner = id;
        lastTrip = f;
        lastTripAt = scheduler.now();
    }

    private void offerTrip(int id, TripFrame frame) {
        Object owner = scheduler.owner();
        if (owner == null || frame.matches(lastTrip)) {
            cancelTripFlush();
            return;
        }
        long delay = TRIP_INTERVAL_MS - (scheduler.now() - lastTripAt);
        if (lastTrip == null || delay <= 0) {
            cancelTripFlush();
            writeTrip(id, frame);
            return;
        }
        if (scheduledOwner != owner) cancelTripFlush();
        pendingTrip = frame;
        pendingComponent = id;
        scheduledOwner = owner;
        if (tripFlush != null) return;
        tripFlush = new Runnable() {
            public void run() {
                if (tripFlush != this) return;
                TripFrame latest = pendingTrip;
                int component = pendingComponent;
                Object owner = scheduledOwner;
                cancelTripFlush();
                if (outputAllowed && owner == scheduler.owner()) writeTrip(component, latest);
            }
        };
        scheduler.later(tripFlush, delay);
    }

    /** Items 7 and 10 are meters; 8/9 and 11/12 are independent display strings and unit enums. */
    static final class Distance {
        final float value;
        final int unit;
        Distance(float value, int unit) { this.value = value; this.unit = unit; }

        /** Raw meters win over the display label. A formatted-only sample is
         * approximate, but its known unit is sufficient for the 9-step guide bar. */
        double guideMeters(int rawMeters) {
            if (rawMeters >= 0) return rawMeters;
            if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f || value > 65535f) return -1d;
            switch (unit) {
                case Cluster.UNIT_M: return value;
                case Cluster.UNIT_KM: return value * 1000d;
                case Cluster.UNIT_MI: return value * 1609.344d;
                case Cluster.UNIT_FT: return value * 0.3048d;
                case Cluster.UNIT_YD: return value * 0.9144d;
                default: return -1d;
            }
        }

        static Distance resolve(int metres, String text, int phoneUnit, int ownerUnit) {
            if (metres >= 0) {
                if (ownerUnit == Car.DISTANCE_MILES) {
                    return metres < 161 ? new Distance(Math.round(metres * 3.28084f), Cluster.UNIT_FT)
                            : new Distance(Math.round(metres / 160.9344d) / 10f, Cluster.UNIT_MI);
                }
                if (ownerUnit == Car.DISTANCE_KM || phoneUnit < 0) {
                    return metres < 1000 ? new Distance(metres, Cluster.UNIT_M)
                            : new Distance(Math.round(metres / 100d) / 10f, Cluster.UNIT_KM);
                }
                switch (phoneUnit) {
                    case 0: return new Distance(Math.round(metres / 100d) / 10f, Cluster.UNIT_KM);
                    case 1: return new Distance(Math.round(metres / 160.9344d) / 10f, Cluster.UNIT_MI);
                    case 3: return new Distance(Math.round(metres / 0.9144d), Cluster.UNIT_YD);
                    case 4: return new Distance(Math.round(metres / 0.3048d), Cluster.UNIT_FT);
                    default: return new Distance(metres, Cluster.UNIT_M);
                }
            }
            if (text == null || text.isEmpty()) return null;
            java.util.regex.Matcher match = DISPLAY.matcher(text.trim());
            if (!match.matches()) return null;
            String number = match.group(1);
            String suffix = match.group(2);
            int unit;
            if (suffix == null) {
                switch (phoneUnit) {
                    case 0: unit=Cluster.UNIT_KM; break;
                    case 1: unit=Cluster.UNIT_MI; break;
                    case 2: unit=Cluster.UNIT_M; break;
                    case 3: unit=Cluster.UNIT_YD; break;
                    case 4: unit=Cluster.UNIT_FT; break;
                    default: return null;
                }
            } else if (suffix.equals("km") || suffix.equals("㎞") || suffix.equals("킬로미터")) unit=Cluster.UNIT_KM;
            else if (suffix.equals("m") || suffix.equals("미터")) unit=Cluster.UNIT_M;
            else if (suffix.equals("mi") || suffix.equals("마일")) unit=Cluster.UNIT_MI;
            else if (suffix.equals("yd") || suffix.equals("야드")) unit=Cluster.UNIT_YD;
            else unit=Cluster.UNIT_FT;
            // Korean numeric strings use comma grouping; also accept one unambiguous decimal comma.
            if (number.indexOf(',') >= 0) {
                if (GROUPED.matcher(number).matches()) number = number.replace(",", "");
                else if (number.indexOf(',') == number.lastIndexOf(',') && number.indexOf('.') < 0)
                    number = number.replace(',', '.');
                else return null;
            }
            try {
                float result = Float.parseFloat(number);
                return Float.isInfinite(result) || result > 65535f ? null : new Distance(result, unit);
            } catch (NumberFormatException ignored) { return null; }
        }

        static final java.util.regex.Pattern DISPLAY = java.util.regex.Pattern.compile(
                "^([0-9]+(?:[.,][0-9]+)*)[\\s\u00a0\u202f]*(km|m|mi|yd|ft|㎞|킬로미터|미터|마일|야드|피트)?$");
        static final java.util.regex.Pattern GROUPED = java.util.regex.Pattern.compile(
                "^[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?$");
    }

    /** up to 3 upcoming turns after this one (updateTPTurnList), distances in the owner's units */
    void sendAhead(Integer id, Update u) {
        int[] list = u == null ? null : u.list;
        if (id == null || list == null || list.length <= 1) return;
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        if (m == null) return;
        int count = 0;
        for (int k = 1; k < list.length && count < 3; k++) {
            Maneuver x = m.get(list[k]);
            if (x != null && x.distance > 0) count++;
        }
        if (count == 0) return;
        int owner = Car.distanceUnit(context);
        Cluster.Turn[] turns = new Cluster.Turn[count];
        int n = 0;
        for (int k = 1; k < list.length && n < 3; k++) {
            Maneuver x = m.get(list[k]);
            if (x == null || x.distance <= 0 || n >= count) continue;
            Cluster.Turn t = new Cluster.Turn();
            t.type = aheadIcon(x);
            t.direction = 0;
            t.traffic = 0;
            t.no = n + 1;
            t.name = x.afterRoad == null ? "" : x.afterRoad;
            t.exitNumber = "";
            int d = x.distance;
            float f = d;
            int unit = Cluster.UNIT_M;
            if (owner == Car.DISTANCE_KM) {
                if (d >= 1000) {
                    unit = Cluster.UNIT_KM;
                    f = d / 1000f;
                }
            } else if (owner == Car.DISTANCE_MILES) {
                if (d < 161) {
                    unit = Cluster.UNIT_FT;
                    f = d * 3.28084f;
                } else {
                    unit = Cluster.UNIT_MI;
                    f = d / 1609f;
                }
            }
            if (f < 0f) f = 0f;
            if (f > 65535f) f = 65535f;
            t.distance = f;
            t.unit = unit;
            turns[n++] = t;
        }
        if (n > 0) cluster.turnList(turns);
    }

    /** one display component's route guidance state (DiPO e/c) */
    static final class Update {
        int state, maneuverState, distLeft, distLeftUnits, distNext, nextUnits, count, laneIndex, laneCount;
        String road, destination, distLeftStr, nextStr, sourceName;
        long eta, arrivalFromDuration;
        int[] list;
        boolean visible, lanesShown, laneSessionEnded, supports, maneuverStatePresent;
        int inputFields,distanceCleared; // Presence mask; clear1=new maneuver,2=display-only,4=invalid raw.

        /** Component IDs come from the phone and may include zero. */
        Update(int id, Iap2NotiMsg msg) {
            if (msg == null || id < 0) return;
            clear();
            read(id, msg);
        }

        void clear() {
            inputFields=0;distanceCleared=0;
            state = -1;
            maneuverState = 1;
            maneuverStatePresent = false;
            road = "";
            destination = "";
            eta = 0;
            arrivalFromDuration = 0;
            distLeft = -1;
            distLeftStr = "";
            distLeftUnits = -1;
            distNext = -1;
            nextStr = "";
            nextUnits = -1;
            list = null;
            count = 0;
            visible = false;
            laneIndex = -1;
            laneCount = -1;
            lanesShown = false;
            laneSessionEnded = false;
            sourceName = "";
            supports = false;
        }

        /** the fields this message carries (by iAP2 type: 1/5 uint8/16, 2/6 uint32, 3/7 uint64, 8 enum, 9 bool, 10 string) */
        void read(int id, Iap2NotiMsg msg) {
            Iap2NotiMsg.Iap2ObjGroup g = msg == null ? null : msg.getItemGroup();
            if (g == null) return;
            inputFields=0;distanceCleared=0;
            int previousIndex = list == null || list.length == 0 ? -1 : list[0];
            boolean rawNext = false, textNext = false, rawTrip = false, textTrip = false;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) continue;
                int item = it.getItemId(), n = it.getItemObjCount();
                if(item>=0 && item<=20) inputFields|=1<<item;
                switch (it.getItemParamType()) {
                    case 1:
                    case 5: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        switch (item) {
                            case 13:
                                if (v != null) {
                                    list = v.clone();
                                } else {
                                    list = null;
                                }
                                break;
                            case 14: count = v != null && v.length > 0 ? v[0] : 0; break;
                            case 16: laneIndex = v != null && v.length > 0 ? v[0] : -1; break;
                            case 17: laneCount = v != null && v.length > 0 ? v[0] : 0; break;
                        }
                        break;
                    }
                    case 2:
                    case 6: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null || v.length == 0) break;
                        if (item == 7 && v[0] >= 0) { distLeft = v[0]; rawTrip = true; }
                        else if (item == 10) { distNext = v[0] >= 0 ? v[0] : -1; rawNext = true; }
                        break;
                    }
                    case 3:
                    case 7: {
                        long[] v = n > 0 ? it.getItemLongArray() : null;
                        if (v == null || v.length == 0) break;
                        if (item == 5) eta = v[0];
                        else if (item == 6 && v[0] >= 0 && v[0] <= 604800L)
                            arrivalFromDuration = System.currentTimeMillis() + v[0] * 1000L;
                        break;
                    }
                    case 8: {
                        int[] v = it.getItemIntArray();
                        if (v == null || v.length == 0) break;
                        switch (item) {
                            case 1: state = v[0]; break;
                            case 2: maneuverState = v[0]; maneuverStatePresent = true; break;
                            case 9: distLeftUnits = v[0]; break;
                            case 12: nextUnits = v[0]; break;
                        }
                        break;
                    }
                    case 9: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null || v.length == 0) break;
                        if (item == 15) visible = v[0] != 0;
                        else if (item == 18) lanesShown = v[0] != 0;
                        else if (item == 20) supports = v[0] != 0;
                        break;
                    }
                    case 10: {
                        String[] v = n > 0 ? it.getItemStringArray() : null;
                        if (v == null || v.length == 0) break;
                        switch (item) {
                            case 3: road = v[0]; break;
                            case 4: destination = v[0] == null ? "" : v[0]; break;
                            case 8: distLeftStr = v[0] == null ? "" : v[0]; textTrip = true; break;
                            case 11: nextStr = v[0] == null ? "" : v[0]; textNext = true; break;
                            case 19: sourceName = v[0]; break;
                        }
                        break;
                    }
                }
            }
            int index = list == null || list.length == 0 ? -1 : list[0];
            if (previousIndex >= 0 && index != previousIndex) {
                distanceCleared|=1;
                if (!rawNext) distNext = -1;
                if (!textNext) nextStr = "";
            }
            // A new formatted-only fix can supersede an older raw sample. A raw
            // field in this same packet takes priority regardless of item order.
            if (textNext && !rawNext) { distNext = -1; distanceCleared|=2; }
            if (rawNext && distNext < 0 && !textNext) { nextStr = "";distanceCleared|=4; }
            if (textTrip && !rawTrip && Distance.resolve(-1, distLeftStr, distLeftUnits, -1) != null) distLeft = -1;
        }
    }

    /** one maneuver (DiPO e/v) */
    static final class Maneuver {
        int index, type, distance, distUnits, side, junction, exit, linkedLane;
        String description, afterRoad, distStr;
        int[] angles;

        // Apple CPManeuver: item 11 is the selected road, item 10 represents the
        // other arms. A missing selected bearing is not the same as 0 (12 o'clock).
        int textAngle;
        boolean textUTurn;
        private static final java.util.regex.Pattern CLOCK_TEXT = java.util.regex.Pattern.compile(
            "(?:(?:회전교차로|교차로|로터리)(?:에서)?\\s+)?(1[0-2]|[1-9])\\s*시(?:\\s*(30)\\s*분)?\\s*방향"
            + "(?:으로)?(?:\\s*(?:진출|진입|진행)(?:하세요|합니다)?)?[.!]?");
        private static final java.util.regex.Pattern UTURN_TEXT = java.util.regex.Pattern.compile(
            "(?:(?:교차로|사거리|삼거리)에서\\s+)?유턴(?:하세요|합니다)?[.!]?");

        private static int instructionAngle(String text) {
            if (text == null) return NO_ANGLE;
            java.util.regex.Matcher clock = CLOCK_TEXT.matcher(text.trim());
            if (!clock.matches()) return NO_ANGLE;
            int angle = (Integer.parseInt(clock.group(1)) % 12) * 30 + (clock.group(2) != null ? 15 : 0);
            return angle <= 180 ? angle : angle - 360;
        }

        private void setInstruction(String value) {
            description = value == null ? "" : value.trim();
            textAngle = instructionAngle(description);
            textUTurn = UTURN_TEXT.matcher(description).matches();
        }

        private int angle() { return validAngle(exit) ? exit : textAngle; }
        private boolean neutralType() { return type == 0 || type == 5 || type < 0 || type > 53; }

        private boolean selectedAtJunction() {
            return validAngle(exit) && (junction == 1 || (angles != null && angles.length > 1));
        }
        private boolean uTurn() { return type == 4 || type == 18 || type == 19 || type == 26 || (neutralType() && textUTurn); }

        int primaryIcon(int state) {
            if (neutralType()) {
                if (textUTurn) return junction == 1 ? 102 : side == 1 ? 73 : 72;
                if (validAngle(textAngle) || selectedAtJunction()) {
                    return junction == 1 ? roundaboutIcon(angle()) : INTERSECTION_ICONS[sector(angle())];
                }
                if (type != 5) return 0;
            }
            // Use the legacy zero-angle default only for unrelated maneuvers whose
            // stock mapping accepts no geometry (e.g. ramps and destination).
            int selected = angle();
            if (!validAngle(selected) && !isRoundaboutType(type) && junction != 1) selected = 0;
            return icon(type, side, junction, selected, state);
        }

        String displayRoad() {
            String label = "";
            int selected = angle();
            if (uTurn()) {
                label = type == 19 || junction == 1 ? "회전교차로 유턴" : "유턴";
            } else if (isRoundaboutType(type) && !validAngle(selected)) {
                if (type >= 28 && type <= 46) label = (type - 27) + "번째 출구";
                else label = type == 6 ? "회전교차로 진입" : "회전교차로 진출";
            } else if (validAngle(selected) && clockUseful(selected)) {
                int bearing = (selected + 360) % 360;
                int halfHours = ((bearing + 7) / 15) % 24;
                int hour = halfHours / 2;
                label = (bearing % 15 == 0 ? "" : "약 ") + (hour == 0 ? 12 : hour)
                        + (halfHours % 2 == 0 ? "시 방향" : "시 30분 방향");
            }
            // No tunnel-specific ManeuverType exists in the public CarPlay enum.
            // Preserve the phone's supplied tunnel instruction instead of losing
            // it when the phone describes that maneuver as straight/follow-road.
            // This changes text only; it does not invent a cluster tunnel glyph.
            if (label.isEmpty() && (type == 3 || type == 5) && description != null
                    && description.contains("터널")) label = description;
            String road = afterRoad == null ? "" : afterRoad.trim();
            if (label.isEmpty()) return road;
            if (label.equals(road)) return road;
            // Apps sometimes put a complete instruction in roadFollowingManeuver,
            // e.g. "회전교차로에서 5시 방향". Retain that richer wording once.
            // Match a complete instruction, never a substring in a road/POI name.
            int labelAngle = instructionAngle(label), roadAngle = instructionAngle(road);
            if (validAngle(labelAngle) && labelAngle == roadAngle) return road;
            if ((label.equals("유턴") || label.equals("회전교차로 유턴"))
                    && UTURN_TEXT.matcher(road).matches()) return road;
            return road.isEmpty() ? label : label + " · " + road;
        }

        private boolean clockUseful(int selected) {
            if (isRoundaboutType(type) || junction == 1 || (neutralType() && selectedAtJunction())) return true;
            if ((type == 1 || type == 13 || type == 20 || type == 47 || type == 49) && selected >= 0) return false;
            if ((type == 2 || type == 14 || type == 21 || type == 48 || type == 50) && selected <= 0) return false;
            if (validAngle(textAngle)) return true;
            boolean directional = type == 1 || type == 2 || type == 3 || type == 13 || type == 14
                    || type == 20 || type == 21 || (type >= 47 && type <= 50);
            if (!directional) return false;
            if (angles != null && angles.length > 1) return true;
            int magnitude = Math.abs(selected);
            return magnitude != 0 && magnitude != 90;
        }

        Maneuver(int index, int id, Iap2NotiMsg msg) {
            if (msg == null) return;
            clear();
            read(index, id, msg);
        }

        void clear() {
            index = -1;
            description = "";
            type = 0;
            afterRoad = "";
            distance = 0;
            distStr = "";
            distUnits = 2;
            side = 0;
            junction = 0;
            angles = null;
            exit = NO_ANGLE;
            textAngle = NO_ANGLE;
            textUTurn = false;
            linkedLane = -1;
        }

        /** iAP2 maneuver deltas retain the previous fields for the same index. */
        void read(int index, int id, Iap2NotiMsg msg) {
            Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
            // Reused maneuver indexes can acquire a new type during a reroute.
            // Clear old geometry/instructions before applying this entire delta in wire order.
            if (type != 0) {
                for (int k = 0; k < g.getItemCnt(); k++) {
                    Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                    if (it == null || it.getItemId() != 3 || it.getItemParamType() != 8) continue;
                    int[] value = it.getItemIntArray();
                    if (value != null && value.length > 0 && value[0] != type) clear();
                    break;
                }
            }
            this.index = index;
            boolean armsSeen = false, armsInvalid = false;
            java.util.ArrayList<Integer> newArms = new java.util.ArrayList<Integer>();
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) continue;
                int item = it.getItemId(), n = it.getItemObjCount();
                switch (it.getItemParamType()) {
                    case 1: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 10) {
                            armsSeen = true;
                            if (v == null || v.length == 0 || v.length > 32
                                    || newArms.size() + v.length > 32) armsInvalid = true;
                            else for (int arm : v) newArms.add(arm);
                        } else if (item == 11) {
                            exit = v != null && v.length == 1 ? selectedAngle(v[0]) : NO_ANGLE;
                        }
                        break;
                    }
                    case 5: {
                        int[] v = it.getItemIntArray();
                        if (item == 12) linkedLane = v != null && v.length > 0 ? v[0] : -1;
                        break;
                    }
                    case 6: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 5 && v != null && v.length > 0) distance = v[0];
                        break;
                    }
                    case 8: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null || v.length == 0) break;
                        switch (item) {
                            case 3: type = v[0]; break;
                            case 7: distUnits = v[0]; break;
                            case 8: side = v[0]; break;
                            case 9: junction = v[0]; break;
                        }
                        break;
                    }
                    case 10: {
                        String[] v = it.getItemStringArray();
                        if (v == null || v.length == 0) break;
                        switch (item) {
                            case 2: setInstruction(v[0]); break;
                            case 4: afterRoad = v[0]; break;
                            case 6: distStr = v[0]; break;
                        }
                        break;
                    }
                }
            }
            if (armsSeen) {
                angles = armsInvalid ? null : new int[newArms.size()];
                if (angles != null) for (int i = 0; i < angles.length; i++) angles[i] = newArms.get(i);
            }
        }
    }
}
