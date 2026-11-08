// Parser tests adapted from seogaemo/hmg-seogaemo 26eeb97e0a745528b438d7a946be5450fadbfd04; AGPL-3.0-or-later.
package hmgapi.mods.wireless.dipo;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Cluster;

public final class CpLaneDecoderTest {
    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        System.out.println("ok    " + name);
    }
    static byte[] join(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }
    static byte[] words(int... values) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int v : values) { b.write(v >> 8); b.write(v); }
        return b.toByteArray();
    }
    static byte[] tlv(int id, byte[] v) { return join(words(v.length + 4, id), v); }
    static byte[] lane(int index, int status, int highlight, int... angles) {
        return tlv(2, join(tlv(0, words(index)), tlv(1, new byte[]{(byte)status}),
                tlv(2, words(angles)), tlv(3, words(highlight))));
    }
    static byte[] message(int index, byte[]... lanes) {
        byte[] body = join(tlv(0, words(1)), tlv(1, words(index)), join(lanes), tlv(3, new byte[]{65,66,0}));
        return join(words(0x4040, body.length + 6, 0x5204), body);
    }
    static byte[] frame(byte[] payload) {
        byte[] b = new byte[payload.length + 10];
        b[0] = (byte)255; b[1] = 0x5a; b[2] = (byte)(b.length >> 8); b[3] = (byte)b.length; b[7] = 10;
        int sum = 0;
        for (int i=0; i<8; i++) sum += b[i];
        b[8] = (byte)-sum;
        System.arraycopy(payload, 0, b, 9, payload.length);
        sum = 0; for (byte v : payload) sum += v;
        b[b.length-1] = (byte)-sum;
        return b;
    }
    public static void main(String[] args) throws Exception {
        byte[] msg = message(7, lane(1, 2, 90, 0, 90), lane(0, 0, 1000, -90));
        CpLaneDecoder.Info info = CpLaneDecoder.decode(msg, 0, msg.length);
        check("decode signed angles, sorted lanes, preferred status, sentinel and description length",
                info.index==7 && info.component==1 && info.descriptionLength==3 && info.lanes.length==2
                && info.lanes[0].angles[0]==-90 && info.lanes[0].highlight==1000 && info.lanes[1].status==2);
        byte[] repeated=message(2,tlv(2,join(tlv(0,words(0)),tlv(1,new byte[]{1}),
                tlv(2,words(-45)),tlv(2,words(0,45)),tlv(3,words(1000)))));
        check("repeated angle TLVs accumulate",Arrays.equals(CpLaneDecoder.decode(repeated,0,repeated.length).lanes[0].angles,new int[]{-45,0,45}));
        byte[] other=msg.clone();other[5]=1;
        check("other control ids ignored",CpLaneDecoder.decode(other,0,other.length)==null);
        for (int n=0; n<msg.length; n++) {
            try { CpLaneDecoder.decode(Arrays.copyOf(msg,n),0,n); throw new AssertionError("truncation " + n); }
            catch (IllegalArgumentException expected) { }
        }
        byte[] malformed=msg.clone(); malformed[6]=0; malformed[7]=0;
        try { CpLaneDecoder.decode(malformed,0,malformed.length); throw new AssertionError("zero TLV"); }
        catch (IllegalArgumentException expected) { }
        check("all truncations and zero-length TLV rejected", true);
        CpLaneDecoder d = new CpLaneDecoder();
        byte[] f = frame(msg), original=f.clone();
        for (int split=1; split<f.length; split++) {
            d.reset();
            checkSplit(d, f, split);
        }
        check("every split of a link frame reassembles, input unchanged", Arrays.equals(f,original));
        d.reset(); byte[] many=join(f,f);
        check("multiple link frames per callback", d.feed(many,many.length).length==2);
        d.reset(); int mid=msg.length/2;
        byte[] fragmented=join(frame(Arrays.copyOfRange(msg,0,mid)),frame(Arrays.copyOfRange(msg,mid,msg.length)));
        check("control message spanning link frames", d.feed(fragmented,fragmented.length).length==1);
        d.reset(); byte[] coalesced=frame(join(msg,msg));
        check("multiple controls per link", d.feed(coalesced,coalesced.length).length==2);
        byte[] corrupt=f.clone(); corrupt[corrupt.length-1]++;
        d.reset(); check("checksum errors dropped", d.feed(corrupt,corrupt.length).length==0 && d.takeErrors()>0);
        java.util.Random random = new java.util.Random(1);
        for(int i=0;i<1000;i++) { byte[] garbage=new byte[128]; random.nextBytes(garbage); d.feed(garbage,garbage.length); }
        d.reset(); check("garbage/reset recovery", d.feed(f,f.length).length==1);
        try { byte[][] excess=new byte[9][]; for(int i=0;i<9;i++) excess[i]=lane(i,1,1000,0);
            byte[] m=message(1,excess); CpLaneDecoder.decode(m,0,m.length); throw new AssertionError("capacity");
        } catch(IllegalArgumentException expected) { }
        try { byte[] m=message(1,lane(0,1,1000,new int[17])); CpLaneDecoder.decode(m,0,m.length); throw new AssertionError("angles capacity"); }
        catch(IllegalArgumentException expected) { }
        check("lane and angle bounds enforced",true);
        System.out.println("CpLaneDecoderTest: parser split/bounds/checksum cases passed");
    }
    static void checkSplit(CpLaneDecoder d, byte[] f, int split) {
        if(d.feed(Arrays.copyOfRange(f,0,split),split).length!=0 ||
                d.feed(Arrays.copyOfRange(f,split,f.length),f.length-split).length!=1)
            throw new AssertionError("split " + split);
    }
}
