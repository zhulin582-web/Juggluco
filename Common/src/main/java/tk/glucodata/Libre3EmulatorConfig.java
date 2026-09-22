/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Configuration shared verbatim by the phone, watch and NFC profile. */
public final class Libre3EmulatorConfig {
    public String name = "Default";
    public String serial = "0PMEA8WHG";
    public String targetNode = ""; // Empty: this device. Otherwise a connected Wear node ID.
    public String address = "";    // Address observed over the air, never the adapter's guessed address.
    public long pin = 0x44791057L;
    public long startTime = System.currentTimeMillis()/1000L - 86400;
    public int warmupMinutes = 60, wearMinutes = 20160, patchState = 4;
    public boolean unused = false;
    public int glucoseMode = 0; // 0 fixed, 1 sine wave, 2 latest Juggluco value.
    public int glucose = 100, amplitude = 25, periodMinutes = 120, intervalSeconds = 60;
    public byte[] certificate = unhex("01713a731cb5007ae0a44f04109f4ad33d00a86cc225c5b06ed38d124f7225ca63aeaa0c82e6d5b88519f8c8cb3ca195e3103c6fe8081e54ed41c5aef6817e4c1e082710d4a7539b44c27682d641dc2b0d3df406887e2d093f2cb827dc245a5af4a31dc19f8d0b0a716c0a92c64eb2b2dbb40aa45a47874a1780d8ad1c4784d05c987fd5359cd5b0f125028a");

    public void validate() {
        if(name == null || name.trim().isEmpty() || name.length()>80) throw new IllegalArgumentException("Invalid profile name");
        if(address == null || targetNode == null || targetNode.length()>256) throw new IllegalArgumentException("Invalid Bluetooth host");
        if(patchState<0 || patchState>255) throw new IllegalArgumentException("Patch state must be 0 to 255");
        nfc(address.isEmpty() ? "00:00:00:00:00:01" : address);
        if(certificate == null || certificate.length != 140 || certificate[11] != 4)
            throw new IllegalArgumentException("A complete 140-byte sensor certificate is required");
        if(glucoseMode<0 || glucoseMode>2 || glucose<39 || glucose>501 || amplitude<0 || amplitude>200 ||
           periodMinutes<2 || periodMinutes>10080 || intervalSeconds<1 || intervalSeconds>3600)
            throw new IllegalArgumentException("Invalid glucose settings");
    }
    public Libre3NfcProfile nfc(String bluetoothAddress) {
        return new Libre3NfcProfile(serial,pin,startTime,warmupMinutes,wearMinutes,bluetoothAddress,unused?1:patchState);
    }
    public byte[] encode() {
        validate();
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(0x4c334531); out.writeUTF(name); out.writeUTF(serial);
            out.writeUTF(targetNode); out.writeUTF(address); out.writeLong(pin); out.writeLong(startTime);
            out.writeInt(warmupMinutes); out.writeInt(wearMinutes); out.writeInt(patchState); out.writeBoolean(unused);
            out.writeInt(glucoseMode); out.writeInt(glucose); out.writeInt(amplitude); out.writeInt(periodMinutes);
            out.writeInt(intervalSeconds); out.write(certificate); out.flush(); return bytes.toByteArray();
        } catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Libre3EmulatorConfig decode(byte[] bytes) {
        if(bytes==null || bytes.length>4096) throw new IllegalArgumentException("Invalid emulator configuration");
        try {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readInt()!=0x4c334531) throw new IOException("Unknown emulator configuration version");
            Libre3EmulatorConfig c=new Libre3EmulatorConfig();
            c.name=in.readUTF(); c.serial=in.readUTF(); c.targetNode=in.readUTF(); c.address=in.readUTF();
            c.pin=in.readLong(); c.startTime=in.readLong(); c.warmupMinutes=in.readInt(); c.wearMinutes=in.readInt();
            c.patchState=in.readInt(); c.unused=in.readBoolean(); c.glucoseMode=in.readInt(); c.glucose=in.readInt();
            c.amplitude=in.readInt(); c.periodMinutes=in.readInt(); c.intervalSeconds=in.readInt();
            c.certificate=new byte[140]; in.readFully(c.certificate);
            if(in.available()!=0) throw new IOException("Trailing configuration bytes");
            c.validate(); return c;
        } catch(IOException ex) { throw new IllegalArgumentException("Invalid emulator configuration",ex); }
    }
    public Libre3EmulatorConfig copy() { return decode(encode()); }
    /** All wire-visible settings must agree before NFC is enabled. */
    public String profileId() {
        Libre3EmulatorConfig c=copy(); c.name="Default"; c.targetNode=""; c.address="";
        try { return hex(MessageDigest.getInstance("SHA-256").digest(c.encode())); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    public String authorizationId() {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update((serial+":"+pin+":"+startTime+":"+unused).getBytes(StandardCharsets.US_ASCII));
            return hex(digest.digest(certificate));
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder(bytes.length*2);
        for(byte b:bytes) s.append("0123456789ABCDEF".charAt((b&255)>>>4)).append("0123456789ABCDEF".charAt(b&15));
        return s.toString();
    }
    public static byte[] unhex(String text) {
        String s=text.replaceAll("\\s", "");
        if((s.length()&1)!=0 || !s.matches("[0-9A-Fa-f]*")) throw new IllegalArgumentException("Invalid hexadecimal bytes");
        byte[] b=new byte[s.length()/2];
        for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return b;
    }
}
