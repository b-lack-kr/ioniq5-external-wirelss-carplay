// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.security.MessageDigest;

/** Bind AP operations to one supported USB device, including its concurrent netdevs. */
public final class SafeInterface {
    static String readIndex(File root, String iface) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(new File(root, "sys/class/net/" + iface + "/ifindex")));
        try { String s = reader.readLine(); if (s == null || !s.matches("[1-9][0-9]{0,9}")) throw new Exception("invalid ifindex"); return s; }
        finally { reader.close(); }
    }
    static String busdev(UsbIds.Identity identity) throws Exception {
        if (!identity.method.equals("ancestry")) return identity.busdev;
        File d = new File(identity.path);
        return UsbIds.decimal(new File(d,"busnum")) + "-" + UsbIds.decimal(new File(d,"devnum"));
    }
    public static String selection(File root) throws Exception { return selection(root, null); }
    static String selection(File root, String requested) throws Exception {
        File net = new File(root, "sys/class/net");
        String[] names = net.list();
        if (names == null) return null;
        String chosen = null;
        long highest = -1;
        UsbIds.Identity owner = null;
        for (String name : names) {
            if (!name.startsWith("rtwlan")) continue;
            if (!name.matches("rtwlan[0-9]{1,3}")) return null;
            UsbIds.Identity candidate = UsbIds.identify(root, name);
            if (candidate == null) return null;
            if (owner != null && (!owner.same(candidate) || !busdev(owner).equals(busdev(candidate)))) return null;
            owner = candidate;
            long index = Long.parseLong(readIndex(root, name));
            // Preserve upstream HKG's highest-ifindex choice when one physical
            // adapter exposes concurrent Realtek interfaces.
            if ((requested == null && index > highest) || name.equals(requested)) {
                chosen = name; highest = index;
            }
        }
        if (chosen == null || (requested != null && !chosen.equals(requested))) return null;
        String index = readIndex(root, chosen);
        UsbIds.Identity first = UsbIds.identify(root, chosen);
        if (first == null) return null;
        String busdev = busdev(first);
        boolean ac = new File(root, "proc/net/rtl88x2bu/" + chosen).isDirectory();
        boolean ax = new File(root, "proc/net/rtl8851bu/" + chosen).isDirectory();
        if (ac == ax || (ac && !first.family.equals("88x2bu")) ||
                (ax && !first.family.equals("8851bu"))) return null;
        UsbIds.Identity second = UsbIds.identify(root, chosen);
        if (!first.same(second) || !index.equals(readIndex(root, chosen)) || !busdev.equals(busdev(second))) return null;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest((chosen+"|"+index+"|"+first.path+"|"+first.id()+"|"+first.family+"|"+busdev).getBytes("UTF-8"));
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) token.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return chosen + " " + index + " " + token.toString();
    }
    public static String select(File root) throws Exception { String s=selection(root); return s==null ? null : s.substring(0,s.indexOf(' ')); }

    public static void main(String[] args) {
        // Never allow a caller to supply a different root on the vehicle.
        try {
            if (args.length == 3 && "check".equals(args[0])) {
                if (!args[1].matches("rtwlan[0-9]{1,3}") || !args[2].matches("[0-9a-f]{64}")) System.exit(2);
                String current = selection(new File("/"), args[1]);
                System.exit(current != null && current.endsWith(" " + args[2]) ? 0 : 1);
            }
            if (args.length != 0) System.exit(2);
            String selected = selection(new File("/"));
            if (selected == null) System.exit(1);
            System.out.println(selected);
        } catch (Throwable t) { System.exit(1); }
    }
}
