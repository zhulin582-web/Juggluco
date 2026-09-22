/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Immutable NFC scan data. Times are Unix seconds; durations are minutes. */
public final class Libre3NfcProfile {
    public static final int USB_SIZE = 64;
    private static final String ALPHABET = "0123456789ACDEFGHJKLMNPQRTUVWXYZ";
    private final byte[] info;
    private final byte[] pairing;
    private final byte[] uid;

    /** Uses the European Libre 3/3+ identity fields seen in the supplied captures. */
    public Libre3NfcProfile(String serial, long pin, long startTime,
                           int warmupMinutes, int wearMinutes,
                           String deviceAddress, int patchState) {
        this(serial, pin, startTime, warmupMinutes, wearMinutes, deviceAddress, patchState,
             1, 1, generationFor(wearMinutes), 0x0104021eL, 4);
    }

    /** Explicit identity fields for sensor variants with different A1 metadata. */
    public Libre3NfcProfile(String serial, long pin, long startTime,
                           int warmupMinutes, int wearMinutes,
                           String deviceAddress, int patchState,
                           int securityVersion, int localization, int generation,
                           long firmwareVersion, int productType) {
        uid = uidFromSerial(serial);
        unsigned(pin, 0xffffffffL, "PIN");
        unsigned(startTime, 0xffffffffL, "start time");
        unsigned(warmupMinutes, 1275, "warmup minutes");
        if (warmupMinutes % 5 != 0) throw new IllegalArgumentException("Warmup must be a multiple of five minutes");
        unsigned(wearMinutes, 65535, "wear minutes");
        if (wearMinutes == 0) throw new IllegalArgumentException("Wear duration must be positive");
        unsigned(patchState, 255, "patch state");
        unsigned(securityVersion, 65535, "security version");
        unsigned(localization, 65535, "localization");
        unsigned(generation, 65535, "generation");
        unsigned(firmwareVersion, 0xffffffffL, "firmware version");
        unsigned(productType, 255, "product type");
        info = new byte[29];
        info[1] = (byte)0xa5;
        put16(info, 3, securityVersion);
        put16(info, 5, localization);
        put16(info, 7, generation);
        put16(info, 9, wearMinutes);
        put32(info, 11, firmwareVersion);
        info[15] = (byte)productType;
        info[16] = (byte)(warmupMinutes / 5);
        info[17] = (byte)patchState;
        System.arraycopy(serial.getBytes(StandardCharsets.US_ASCII), 0, info, 18, 9);
        put16(info, 27, appCrc(info, 3, 24));
        pairing = new byte[19];
        pairing[1] = (byte)0xa5;
        byte[] address = addressBytes(deviceAddress);
        for (int i = 0; i < 6; i++) pairing[3 + i] = address[5 - i];
        put32(pairing, 9, pin);
        put32(pairing, 13, startTime);
        put16(pairing, 17, appCrc(pairing, 3, 14));
    }

    private Libre3NfcProfile(byte[] first, byte[] second) {
        checkReply(first, 29);
        checkReply(second, 19);
        info = first.clone();
        pairing = second.clone();
        uid = uidFromSerial(new String(info, 18, 9, StandardCharsets.US_ASCII));
    }

    /** Accepts the arrays returned by NfcV.transceive, including Libre's CRC. */
    public static Libre3NfcProfile fromScan(byte[] first, byte[] second) {
        return new Libre3NfcProfile(first, second);
    }

    /** Reuses all A1 variant fields and changes only the emulated connection. */
    public Libre3NfcProfile withConnection(long pin, long startTime, String address) {
        return new Libre3NfcProfile(serial(), pin, startTime, warmupMinutes(), wearMinutes(),
                address, patchState(), u16(info, 3), u16(info, 5), u16(info, 7), u32(info, 11), info[15] & 255);
    }

    public String serial() { return new String(info, 18, 9, StandardCharsets.US_ASCII); }
    public long pin() { return u32(pairing, 9); }
    public long startTime() { return u32(pairing, 13); }
    public int warmupMinutes() { return (info[16] & 255) * 5; }
    public int wearMinutes() { return u16(info, 9); }
    public int patchState() { return info[17] & 255; }
    public String deviceAddress() {
        return String.format(Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
                pairing[8] & 255, pairing[7] & 255, pairing[6] & 255,
                pairing[5] & 255, pairing[4] & 255, pairing[3] & 255);
    }
    public byte[] firstResponse() { return info.clone(); }
    public byte[] secondResponse() { return pairing.clone(); }
    public byte[] nfcUid() { return uid.clone(); }

    public byte[] usbPayload() {
        byte[] data = new byte[USB_SIZE];
        data[0] = 'L'; data[1] = '3'; data[2] = 'N'; data[3] = '1';
        System.arraycopy(uid, 0, data, 4, 8);
        System.arraycopy(info, 0, data, 12, 29);
        System.arraycopy(pairing, 0, data, 41, 19);
        return data;
    }

    public static Libre3NfcProfile fromUsbPayload(byte[] data) {
        if (data == null || data.length != USB_SIZE || data[0] != 'L' || data[1] != '3' ||
                data[2] != 'N' || data[3] != '1') throw new IllegalArgumentException("Invalid L3N1 profile");
        for (int i = 60; i < USB_SIZE; i++) {
            if (data[i] != 0) throw new IllegalArgumentException("Unknown profile options");
        }
        Libre3NfcProfile p = fromScan(Arrays.copyOfRange(data, 12, 41), Arrays.copyOfRange(data, 41, 60));
        if (!Arrays.equals(p.uid, Arrays.copyOfRange(data, 4, 12))) {
            throw new IllegalArgumentException("UID does not match serial number");
        }
        return p;
    }

    /** Same calculation as dp::crc16_dp; the result is stored little-endian. */
    public static int appCrc(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("Invalid CRC range");
        }
        int crc = 0xffff;
        for (int i = offset; i < offset + length; i++) {
            int value = data[i] & 255;
            int reversed = Integer.reverse(value) >>> 24;
            crc ^= reversed << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = ((crc << 1) ^ ((crc & 0x8000) != 0 ? 0x1021 : 0)) & 0xffff;
            }
        }
        return crc;
    }

    public static byte[] uidFromSerial(String serial) {
        if (serial == null || serial.length() != 9) throw new IllegalArgumentException("Serial must have nine characters");
        long number = 0;
        for (int i = 0; i < 9; i++) {
            int digit = ALPHABET.indexOf(serial.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("Invalid Libre serial character");
            number = (number << 5) | digit;
        }
        byte[] result = new byte[8];
        for (int i = 0; i < 6; i++) result[i] = (byte)(number >>> (8 * i));
        result[6] = 0x7a; result[7] = (byte)0xe0;
        return result;
    }

    private static int generationFor(int wearMinutes) {
        if (wearMinutes == 20160) return 0;
        if (wearMinutes == 21600) return 1;
        throw new IllegalArgumentException("Use explicit identity fields or fromScan for this wear duration");
    }
    private static byte[] addressBytes(String address) {
        if (address == null || !address.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) {
            throw new IllegalArgumentException("Bluetooth address must be XX:XX:XX:XX:XX:XX");
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) out[i] = (byte)Integer.parseInt(address.substring(i * 3, i * 3 + 2), 16);
        return out;
    }
    private static void checkReply(byte[] data, int size) {
        if (data == null || data.length != size || data[0] != 0 || data[1] != (byte)0xa5 || data[2] != 0 ||
                u16(data, size - 2) != appCrc(data, 3, size - 5)) {
            throw new IllegalArgumentException("Invalid Libre NFC reply or application CRC");
        }
    }
    private static void unsigned(long value, long max, String name) {
        if (value < 0 || value > max) throw new IllegalArgumentException("Invalid " + name);
    }
    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }
    private static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8) |
                ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }
    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte)value; data[offset + 1] = (byte)(value >>> 8);
    }
    private static void put32(byte[] data, int offset, long value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte)(value >>> (8 * i));
    }
}
