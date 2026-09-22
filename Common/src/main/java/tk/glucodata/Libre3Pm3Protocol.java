/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import java.io.IOException;
import java.util.Arrays;

/** Proxmark NG framing, independent of USB transfer boundaries. */
final class Libre3Pm3Protocol {
    static final int BREAK = 0x0118;
    static final int INFO = 0x03f0;
    static final int SIMULATE = 0x03f1;
    static final int DIAGNOSTIC = 0x03f2;
    static final int DEBUG_STRING = 0x0100;
    static final byte[] INFO_REPLY = {'L', '3', 'N', '1', 1, 64, 0, 0};

    static int capabilities(Reply reply) throws IOException {
        if (!reply.ng || reply.status != 0 || reply.payload.length != INFO_REPLY.length) {
            throw new IOException("Proxmark3 needs the matching L3N1 Libre 3 firmware");
        }
        for (int i = 0; i < 6; i++) {
            if (reply.payload[i] != INFO_REPLY[i]) throw new IOException("Unsupported Libre 3 firmware ABI");
        }
        return u16(reply.payload, 6);
    }

    static void validateDiagnostic(byte[] p) throws IOException {
        if (p.length != 48 || p[0] != 1) throw new IOException("Unsupported RF diagnostic record");
        if (p[1] == 2) {
            int count = p[24] & 255;
            if (count > 20 || count > u16(p, 12)) throw new IOException("Invalid RF diagnostic prefix length");
        } else if (p[1] != 1 && p[1] != 3) {
            throw new IOException("Unknown RF diagnostic type: " + (p[1] & 255));
        }
    }

    /** Decodes the firmware's versioned, 48-byte diagnostic records. */
    static String diagnostic(byte[] p) throws IOException {
        validateDiagnostic(p);
        String prefix = "RF t=" + u32(p, 4) + "ms ";
        if (p[1] == 1 || p[1] == 3) {
            String reason = "";
            if (p[1] == 3) {
                String[] reasons = {"unspecified", "button", "USB command", "receiver error", "USB unavailable"};
                int code = p[2] & 255;
                reason = "STOP reason=" + (code < reasons.length ? reasons[code] : code) + " ";
            }
            return prefix + reason + "HF_mV=" + u32(p, 36) + " peak_mV=" + u32(p, 40) +
                    " rx=" + u32(p, 8) + " tx=" + u32(p, 12) + " badCRC=" + u32(p, 16) +
                    " short=" + u32(p, 20) + " silent=" + u32(p, 24) + " overrun=" + u32(p, 28) +
                    " logDropped=" + u32(p, 32) + " lastRxError=" + (int)u32(p, 44);
        }
        if (p[1] == 2) {
            int count = p[24] & 255;
            String[] outcomes = {"reply attempted", "bad RF CRC", "short/oversize frame", "silent (filtered/unsupported request)"};
            String[] replies = {"none", "UID", "A1 information", "A0/A8 connection", "wrong manufacturer", "unsupported command", "ended sensor"};
            int code = p[2] & 255, reply = p[3] & 255;
            return prefix + "RX#" + u32(p, 8) + " bytes=" + u16(p, 12) + " [" + hex(p, 28, count) +
                    (count < u16(p, 12) ? " ..." : "") + "] " +
                    (code < outcomes.length ? outcomes[code] : "outcome=" + code) +
                    " TX=" + (reply < replies.length ? replies[reply] : reply) + " bytes=" + u16(p, 14) +
                    " startShiftTicks=" + (int)u32(p, 20) + " HF_mV=" + u32(p, 16);
        }
        throw new IOException("Unknown RF diagnostic type: " + (p[1] & 255));
    }

    /** A connection reply was attempted; this does not prove reader reception. */
    static long[] connectionScan(byte[] p) throws IOException {
        validateDiagnostic(p);
        if(p[1]!=2 || p[2]!=0 || p[3]!=3) return null;
        int count=p[24]&255;
        if(count<3 || (p[30]&255)!=0x7a) return null;
        int command=p[29]&255;
        if(command!=0xa0 && command!=0xa8) return null;
        int at=(p[28]&0x20)!=0?11:3;
        if(count<at+4 || u16(p,12)<at+6) return null;
        return new long[]{command==0xa0?1:0,u32(p,28+at)};
    }

    /** App-level bytes: omit inventory polling and the two-byte RF CRC. */
    static String nfcExchange(byte[] p, Libre3NfcProfile profile) throws IOException {
        validateDiagnostic(p);
        if (p[1] != 2) return null;
        int outcome = p[2] & 255, count = p[24] & 255, wireLength = u16(p, 12);
        if ((outcome != 0 && outcome != 3) || wireLength < 4 || count < 2) return null;
        if ((p[28] & 4) != 0 && p[29] == 1) return null;
        int length = wireLength - 2;
        String command = hex(p, 28, Math.min(count, length));
        if (count < length) command += " ... (" + length + " bytes; prefix only)";
        String response;
        if (outcome == 3) {
            response = "<no response: filtered/unsupported request>";
        } else {
            int id = p[3] & 255;
            byte[] bytes = responseBytes(id, profile);
            if (bytes == null || u16(p, 14) != bytes.length + 2) {
                response = "<unavailable: reply=" + id + " RF bytes=" + u16(p, 14) + ">";
            } else {
                response = "[" + hex(bytes, 0, bytes.length) + "]";
            }
        }
        return "NFC #" + u32(p, 8) + " command=[" + command + "] response=" + response;
    }

    // The v2/v3 firmware reports its selected cache entry, not the TX bytes.
    // Keep this table aligned with l3nfc_reply_bytes(); it is cross-tested in C.
    static byte[] responseBytes(int id, Libre3NfcProfile profile) {
        switch (id) {
            case 1:
                byte[] uid = new byte[10];
                System.arraycopy(profile.nfcUid(), 0, uid, 2, 8);
                return uid;
            case 2: return profile.firstResponse();
            case 3: return profile.secondResponse();
            case 4: return new byte[]{1, (byte)0xd3};
            case 5: return new byte[]{1, 1};
            case 6: return new byte[]{0, (byte)0xa5, 1, (byte)0xb0};
            default: return null;
        }
    }

    static String hex(byte[] bytes, int offset, int count) {
        final char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder result = new StringBuilder(count * 3);
        for (int i = offset; i < offset + count; i++) {
            if (i != offset) result.append(' ');
            result.append(digits[(bytes[i] & 255) >>> 4]).append(digits[bytes[i] & 15]);
        }
        return result.toString();
    }

    static byte[] command(int command, byte[] payload) {
        if (payload.length > 512) throw new IllegalArgumentException("Proxmark payload too large");
        byte[] frame = new byte[payload.length + 10];
        frame[0] = 'P'; frame[1] = 'M'; frame[2] = '3'; frame[3] = 'a';
        put16(frame, 4, payload.length | 0x8000);
        put16(frame, 6, command);
        System.arraycopy(payload, 0, frame, 8, payload.length);
        frame[frame.length - 2] = 'a'; frame[frame.length - 1] = '3';
        return frame;
    }

    static final class Reply {
        final int command;
        final int status;
        final boolean ng;
        final byte[] payload;
        Reply(int command, int status, boolean ng, byte[] payload) {
            this.command = command; this.status = status; this.ng = ng; this.payload = payload;
        }
    }

    static final class Decoder {
        private byte[] buffer = new byte[4096];
        private int size;

        void append(byte[] input, int length) throws IOException {
            if (length < 0 || length > input.length || size + length > 65536) {
                throw new IOException("Invalid Proxmark USB stream length");
            }
            if (size + length > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(size + length, buffer.length * 2));
            System.arraycopy(input, 0, buffer, size, length);
            size += length;
        }

        Reply next() throws IOException {
            int skipped = 0;
            while (size - skipped >= 4 && !(buffer[skipped] == 'P' && buffer[skipped + 1] == 'M' &&
                    buffer[skipped + 2] == '3' && buffer[skipped + 3] == 'b')) skipped++;
            discard(skipped);
            if (size < 10) return null;
            int rawLength = u16(buffer, 4);
            int length = rawLength & 0x7fff;
            int end = 10 + length;
            if (size < end + 2) return null;
            int footer = u16(buffer, end);
            int crc = crcA(buffer, end);
            int swappedCrc = ((crc & 255) << 8) | (crc >>> 8);
            if (footer != 0x3362 && footer != swappedCrc) {
                throw new IOException("Invalid Proxmark reply CRC");
            }
            Reply reply = new Reply(u16(buffer, 8), buffer[6], (rawLength & 0x8000) != 0,
                    Arrays.copyOfRange(buffer, 10, end));
            discard(end + 2);
            return reply;
        }

        private void discard(int n) {
            if (n == 0) return;
            System.arraycopy(buffer, n, buffer, 0, size - n);
            size -= n;
        }
    }

    static int crcA(byte[] data, int length) {
        int crc = 0x6363;
        for (int i = 0; i < length; i++) {
            crc ^= data[i] & 255;
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0x8408 : 0);
        }
        return crc & 0xffff;
    }
    private static int u16(byte[] data, int offset) { return (data[offset] & 255) | ((data[offset + 1] & 255) << 8); }
    private static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8) |
                ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }
    private static void put16(byte[] data, int offset, int value) { data[offset] = (byte)value; data[offset + 1] = (byte)(value >>> 8); }
}
