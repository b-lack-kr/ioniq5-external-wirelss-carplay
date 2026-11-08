// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import java.io.File;
import java.io.FileOutputStream;

/** Dedicated diagnostic sink: no inherited stdout FD and no power-policy effects. */
final class PowerTraceFile {
    static final int LIMIT = 65536;
    private final File current, previous;
    PowerTraceFile(File current) {
        this.current = current;
        this.previous = new File(current.getPath() + ".1");
    }
    private static boolean regularOrAbsent(File file) throws Exception {
        return file.getCanonicalFile().equals(file.getAbsoluteFile()) &&
            (!file.exists() || file.isFile());
    }
    private static boolean privateFile(File file) {
        return file.setReadable(false, false) && file.setWritable(false, false) &&
            file.setExecutable(false, false) && file.setReadable(true, true) &&
            file.setWritable(true, true);
    }
    synchronized boolean append(String line) {
        try {
            if (line == null || !line.startsWith("AP_POWER_INPUT ") || line.length() > 2048) return false;
            for (int i = 0; i < line.length(); i++)
                if (line.charAt(i) < 32 || line.charAt(i) > 126) return false;
            if (!regularOrAbsent(current) || !regularOrAbsent(previous) ||
                    !current.getParentFile().isDirectory()) return false;
            byte[] bytes = (line + "\n").getBytes("US-ASCII");
            // Never retain an oversized old file as the rotated backup.
            if (previous.length() > LIMIT && !previous.delete()) return false;
            if (current.length() > LIMIT && !current.delete()) return false;
            if (current.length() + bytes.length > LIMIT) {
                if (previous.exists() && !previous.delete()) return false;
                if (!current.renameTo(previous)) return false;
                if (!privateFile(previous)) return false;
            }
            if (!current.exists() && !current.createNewFile()) return false;
            if (!privateFile(current)) return false;
            FileOutputStream out = new FileOutputStream(current, true);
            try { out.write(bytes); } finally { out.close(); }
            return true;
        } catch (Exception unavailable) {
            // Failure to record diagnostics must never exit the OEM reader,
            // synthesize a state, or delay a power transition with a retry.
            return false;
        }
    }
}
