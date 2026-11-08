package hmgapi.mods.wireless.dipo;

import com.tcc.iap2.Iap2NotiMsg;
import java.util.Arrays;

/** Bounded normalization of the existing OEM notify-122 callback. This does not
 * enable that callback in libiap2session, or install a second transport parser.
 * The target DiPO consumer exposes inner fields 0=index, 1=status, 2=angles,
 * 3=highlight. Whether this firmware delivers the callback needs vehicle evidence. */
final class CpLaneCallback {
    static final int UNKNOWN = 1000;
    static final class Rows {
        final byte[] frame;
        final CpLaneDecoder.Lane[] exact,observed;
        Rows(byte[] frame, CpLaneDecoder.Lane[] exact,CpLaneDecoder.Lane[] observed) {
            this.frame=frame;this.exact=exact;this.observed=observed;
        }
    }
    static final class Row {
        int index=-1, status=-1, highlight=UNKNOWN;
        int[] angles=new int[0];
        boolean exact=true, recommendation=true;
    }
    private CpLaneCallback() {}

    static Rows read(Iap2NotiMsg.Iap2ObjGroup[] groups) {
        if (groups == null || groups.length > 8) throw new IllegalArgumentException("lane capacity");
        Row[] rows=new Row[groups.length];
        boolean exact=true;
        for (int i=0;i<rows.length;i++) { rows[i]=row(groups[i]); exact &= rows[i].exact; }
        return build(rows,exact);
    }

    /** Same normalized representation for the separately isolated raw fallback.
     * The caller owns session/generation checks and must call on the TBT handler. */
    static Rows wireRows(CpLaneDecoder.Lane[] lanes) {
        if(lanes==null || lanes.length>8) throw new IllegalArgumentException("lane capacity");
        Row[] rows=new Row[lanes.length]; boolean exact=true;
        for(int i=0;i<lanes.length;i++) {
            CpLaneDecoder.Lane lane=lanes[i];
            if(lane==null || lane.angles==null || lane.angles.length>16)
                throw new IllegalArgumentException("lane values");
            Row row=new Row(); rows[i]=row;
            row.index=lane.index>=0 && lane.index<8?lane.index:-1;
            row.status=lane.status>=0 && lane.status<=2?lane.status:-1;
            row.angles=lane.angles.clone(); row.highlight=lane.highlight;
            row.exact=row.index>=0 && row.status>=0
                    && (angle(row.highlight) || row.highlight==UNKNOWN)
                    && (row.angles.length>0 || angle(row.highlight));
            if(!angle(row.highlight) && row.highlight!=UNKNOWN) row.recommendation=false;
            for(int a:row.angles) if(!angle(a)) row.exact=false;
            exact &= row.exact;
        }
        return build(rows,exact);
    }

    private static Rows build(Row[] rows,boolean exact) {
        // Only explicit, unique physical indexes determine left-to-right order.
        // Legacy callbacks lacking indexes retain arrival order for F1 only.
        boolean indexed=true;
        for (int i=0;i<rows.length;i++) {
            if (rows[i].index<0) indexed=false;
            for(int j=0;j<i;j++) if(rows[i].index==rows[j].index) indexed=false;
        }
        if(indexed) for(int i=1;i<rows.length;i++) {
            Row value=rows[i]; int j=i;
            while(j>0 && rows[j-1].index>value.index) { rows[j]=rows[j-1]; j--; }
            rows[j]=value;
        }
        exact &= indexed;
        byte[] frame=new byte[81]; frame[0]=(byte)0xf1; frame[1]=(byte)rows.length;
        CpLaneDecoder.Lane[] lanes=new CpLaneDecoder.Lane[rows.length];
        for(int i=0;i<rows.length;i++) {
            Row row=rows[i];
            // A gap cannot prove the road has exactly this topology.
            exact &= row.index==i;
            int[] values=values(row);
            frame[3+i]=(byte)values[0];
            for(int j=0;j<3;j++) frame[19+3*i+j]=(byte)values[1+j];
            lanes[i]=new CpLaneDecoder.Lane(row.index,row.status,row.angles.clone(),row.highlight);
        }
        return new Rows(frame, exact ? lanes : null,lanes);
    }

    static Row row(Iap2NotiMsg.Iap2ObjGroup group) {
        Row row=new Row();
        if(group==null) { row.exact=false; return row; }
        if(group.getItemCnt()<0 || group.getItemCnt()>32) throw new IllegalArgumentException("lane fields");
        int seen=0;
        for(int i=0;i<group.getItemCnt();i++) {
            Iap2NotiMsg.Iap2ObjItem item=group.getObjItem(i);
            if(item==null) { row.exact=false; continue; }
            int id=item.getItemId();
            if(id<0 || id>3) continue;
            if((seen & (1<<id))!=0) { row.exact=false; if(id==1 || id==3) row.recommendation=false; continue; }
            seen |= 1<<id;
            int type=item.getItemParamType();
            if(type!=5 && type!=8) { row.exact=false; if(id==1 || id==3) row.recommendation=false; continue; }
            int[] v=item.getItemIntArray();
            if(v==null || v.length>16) { row.exact=false; if(id==1 || id==3) row.recommendation=false; continue; }
            if(id==2) {
                row.angles=v.clone();
                for(int a:v) if(!angle(a)) row.exact=false;
            } else {
                if(v.length!=1) { row.exact=false; if(id==1 || id==3) row.recommendation=false; continue; }
                if(id==0) { if(v[0]>=0 && v[0]<8) row.index=v[0]; else row.exact=false; }
                if(id==1) { if(v[0]>=0 && v[0]<=2) row.status=v[0]; else row.exact=false; }
                if(id==3) { row.highlight=v[0]; if(!angle(v[0]) && v[0]!=UNKNOWN) { row.exact=false; row.recommendation=false; } }
            }
        }
        if(row.index<0 || row.status<0) row.exact=false;
        if(row.angles.length==0 && !angle(row.highlight)) row.exact=false;
        return row;
    }

    static int[] values(Row row) {
        int[] classes=new int[8]; int count=0, onRoute=0;
        // Phone/SDK versions can include or exclude primary in secondary angles.
        // Union and deduplicate by direction without inventing a recommendation.
        int primary=CpTurns.laneIcon(row.highlight);
        if(primary!=0) { classes[count++]=primary; onRoute=1; }
        for(int a:row.angles) {
            int icon=CpTurns.laneIcon(a); if(icon==0) continue;
            boolean present=false;
            for(int j=0;j<count;j++) if(classes[j]==icon) present=true;
            if(!present && count<classes.length) classes[count++]=icon;
        }
        // Missing/unknown status is never promoted into a usable/recommended lane.
        return CpLaneFrame.lane(Arrays.copyOf(classes,count),onRoute,row.status<0 || !row.recommendation?0:row.status);
    }
    private static boolean angle(int a) { return a>=-180 && a<=180; }
}
