/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import android.content.*;
import com.google.android.gms.wearable.Wearable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Control travels only to the explicitly selected connected Wear node. */
public final class Libre3EmulatorWear {
    private static final String PREFIX="/libre3-emulator/";
    private static final Map<String,Libre3EmulatorAddress> scans=new HashMap<>();
    private static int observerRequest;
    private Libre3EmulatorWear() { }
    static void send(Context c,String node,String command,byte[] data) {
        Libre3EmulatorLog.i("Wear","send command="+command+" node="+node+" bytes="+data.length);
        Wearable.getMessageClient(c).sendMessage(node,PREFIX+command,data).addOnSuccessListener(id->
            Libre3EmulatorLog.i("Wear","sent command="+command+" node="+node+" request="+id)).addOnFailureListener(error->{
            Libre3EmulatorLog.error("Wear","send failed command="+command+" node="+node,error);
            Libre3Emulator.remoteStatus=error.toString();
            if(command.equals("start")) Libre3Emulator.remoteReady=false;
            if(command.equals("observe")) Libre3Emulator.discoveryStatus="Could not ask companion to scan: "+error.getMessage();
        });
    }
    public static boolean receive(Context context,String node,String path,byte[] data) {
        if(!path.startsWith(PREFIX)) return false;
        Libre3EmulatorLog.i("Wear","receive path="+path+" node="+node+" bytes="+(data==null?0:data.length));
        if(android.os.Build.VERSION.SDK_INT<26 || BuildConfig.libreVersion!=3) return true;
        if(data==null || data.length>8192) return true;
        byte[] copy=data.clone(); String command=path.substring(PREFIX.length());
        // Matching application signatures are enforced by the Wear data layer;
        // also require this particular node to be currently connected.
        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes->{
            if(nodes.stream().noneMatch(n->n.getId().equals(node))) { Libre3EmulatorLog.w("Wear","ignore message from disconnected node="+node); return; }
            Libre3Emulator.MAIN.post(()->handle(context.getApplicationContext(),node,command,copy));
        }).addOnFailureListener(e->Libre3EmulatorLog.error("Wear","cannot check connected nodes for incoming message",e));
        return true;
    }
    private static void handle(Context c,String node,String command,byte[] data) {
        try {
            switch(command) {
                case "start": {
                    Libre3EmulatorConfig cfg=Libre3EmulatorConfig.decode(data);
                    Libre3EmulatorService service=Libre3EmulatorService.instance;
                    if(service!=null && Libre3Emulator.running!=null && !service.ownedBy(node))
                        throw new IllegalStateException("The emulator is already controlled locally or by another phone");
                    if(Libre3Emulator.running!=null && !Libre3Emulator.running.profileId().equals(cfg.profileId()))
                        throw new IllegalStateException("Stop Bluetooth before changing its settings");
                    c.startForegroundService(new Intent(c,Libre3EmulatorService.class).setAction("start")
                        .putExtra("owner",node).putExtra("config",cfg.encode())); break;
                }
                case "stop": {
                    Libre3EmulatorService service=Libre3EmulatorService.instance;
                    if(service!=null && service.ownedBy(node)) c.stopService(new Intent(c,Libre3EmulatorService.class));
                    break;
                }
                case "activate": {
                    Libre3EmulatorService service=Libre3EmulatorService.instance;
                    if(service!=null && service.ownedBy(node) && data.length==8)
                        service.activate(new DataInputStream(new ByteArrayInputStream(data)).readLong());
                    break;
                }
                case "query": status(c,node); break;
                case "status": receivedStatus(c,node,data); break;
                case "observe": if(data.length==8) observe(c,node,data,false); break;
                case "observed": {
                    DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));
                    byte[] token=new byte[8]; in.readFully(token); String address=in.readUTF();
                    if(in.available()!=0 || !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) break;
                    if(Libre3Emulator.token!=null && Arrays.equals(token,Libre3Emulator.token)) {
                        Libre3Emulator.observedAddress(address,"companion "+node);
                        Libre3EmulatorService service=Libre3EmulatorService.instance; if(service!=null) service.publish();
                    } else Libre3EmulatorLog.w("Wear","ignore observed address for stale advertising token node="+node);
                    break;
                }
                case "observer-error": {
                    if(Libre3Emulator.running!=null && Libre3Emulator.token!=null) {
                        Libre3Emulator.discoveryStatus="Companion discovery: "+new String(data,StandardCharsets.UTF_8);
                        Libre3EmulatorLog.w("Wear",Libre3Emulator.discoveryStatus);
                    }
                    break;
                }
                case "reset": {
                    Libre3EmulatorConfig cfg=Libre3EmulatorConfig.decode(data);
                    if(Libre3Emulator.running!=null) throw new IllegalStateException("Stop Bluetooth before resetting pairing");
                    new Libre3Emulator.Store(c,cfg).reset();
                    break;
                }
                case "error": {
                    Libre3EmulatorConfig selected=Libre3Emulator.load(c,Libre3Emulator.prefs(c).getString("selected","Default"));
                    if(selected.targetNode.equals(node)) Libre3Emulator.remoteStatus=new String(data,StandardCharsets.UTF_8);
                    break;
                }
                default: Libre3EmulatorLog.w("Wear","unknown command="+command); break;
            }
        } catch(Exception error) { Libre3EmulatorLog.error("Wear","handle command="+command+" node="+node,error); send(c,node,"error",error.toString().getBytes(StandardCharsets.UTF_8)); }
    }
    static void activate(Context c,String node,long start) {
        try { ByteArrayOutputStream b=new ByteArrayOutputStream(); new DataOutputStream(b).writeLong(start); send(c,node,"activate",b.toByteArray()); }
        catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static void status(Context c,String node) {
        try {
            ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(b);
            Libre3EmulatorConfig cfg=Libre3Emulator.running; byte[] token=Libre3Emulator.token;
            out.writeBoolean(cfg!=null && token!=null); out.writeUTF(cfg==null?"":cfg.profileId());
            out.writeUTF(Libre3Emulator.status); out.writeUTF(Libre3Emulator.address);
            out.writeLong(cfg==null?0:new Libre3Emulator.Store(c,cfg).activation());
            out.writeByte(token==null?0:token.length); if(token!=null) out.write(token);
            send(c,node,"status",b.toByteArray());
        } catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void receivedStatus(Context c,String node,byte[] data) throws IOException {
        Libre3EmulatorConfig selected=Libre3Emulator.load(c,Libre3Emulator.prefs(c).getString("selected","Default"));
        if(!selected.targetNode.equals(node)) return;
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));
        boolean ready=in.readBoolean(); String profile=in.readUTF(),state=in.readUTF(),address=in.readUTF();
        long activation=in.readLong(); int n=in.readUnsignedByte();
        if(n!=0 && n!=8) throw new IOException("Invalid watch advertising token");
        byte[] token=new byte[n]; in.readFully(token); if(in.available()!=0) throw new IOException("Trailing watch status bytes");
        Libre3EmulatorLog.i("Wear","status node="+node+" ready="+ready+" profileMatches="+profile.equals(selected.profileId())+
            " address="+address+" state="+state+" tokenBytes="+n);
        if(!node.equals(Libre3Emulator.remoteNode)) { Libre3Emulator.remoteAddress=""; Libre3Emulator.remoteToken=null; }
        Libre3Emulator.remoteNode=node; Libre3Emulator.remoteReady=ready; Libre3Emulator.remoteProfile=profile; Libre3Emulator.remoteStatus=state;
        if(!Arrays.equals(token,Libre3Emulator.remoteToken)) {
            Libre3Emulator.remoteAddress=""; Libre3Emulator.remoteAddressObservedAt=0; Libre3Emulator.remoteDiscoveryStatus="";
        }
        Libre3Emulator.remoteToken=token;
        if(ready && profile.equals(selected.profileId()) && activation>0) new Libre3Emulator.Store(c,selected).activation(activation);
        if(!address.isEmpty() && !address.equals(Libre3Emulator.remoteAddress)) {
            Libre3Emulator.remoteAddress=address; Libre3Emulator.remoteAddressObservedAt=android.os.SystemClock.elapsedRealtime();
            Libre3Emulator.remoteDiscoveryStatus="Address received from watch";
        }
        if(ready && n==8 && profile.equals(selected.profileId()) && Libre3Emulator.remoteAddress.isEmpty()) observe(c,node,token,true);
    }
    static void askObserver(Context c,byte[] token) {
        final int request=++observerRequest;
        Libre3Emulator.discoveryStatus="Looking for a connected Wear companion to observe the address";
        Libre3EmulatorLog.i("Discovery","request observer token="+Libre3EmulatorConfig.hex(token));
        Wearable.getNodeClient(c).getConnectedNodes().addOnSuccessListener(nodes->{
            if(request!=observerRequest || !Arrays.equals(token,Libre3Emulator.token)) return;
            Libre3EmulatorLog.i("Discovery","connected Wear nodes="+nodes.size());
            // Discovery is read-only; request one nearby companion, never start an emulator on every watch.
            if(nodes.isEmpty()) {
                if(!Libre3Emulator.address.isEmpty()) return;
                Libre3Emulator.discoveryStatus="No Wear companion connected. On a second device, open Find another emulator, then Report address to host.";
                Libre3EmulatorLog.w("Discovery",Libre3Emulator.discoveryStatus); return;
            }
            String node=nodes.get(0).getId();
            Libre3Emulator.discoveryStatus="Requested address scan on companion "+nodes.get(0).getDisplayName();
            send(c,node,"observe",token);
            Libre3Emulator.MAIN.postDelayed(()->{
                if(request==observerRequest && Arrays.equals(token,Libre3Emulator.token) && Libre3Emulator.address.isEmpty() &&
                        Libre3Emulator.discoveryStatus.startsWith("Requested address scan")) {
                    Libre3Emulator.discoveryStatus="No address reply from companion. Open its emulator screen, allow scan permissions and retry; collect its Libre3Emu logs too.";
                    Libre3EmulatorLog.w("Discovery",Libre3Emulator.discoveryStatus+" node="+node);
                }
            },30000);
        }).addOnFailureListener(e->{
            Libre3EmulatorLog.error("Discovery","getConnectedNodes failed",e);
            if(request==observerRequest && Arrays.equals(token,Libre3Emulator.token) && Libre3Emulator.address.isEmpty())
                Libre3Emulator.discoveryStatus="Wear discovery unavailable: "+e.getMessage()+". Use Find another emulator on a second device.";
        });
    }
    static void rediscover(Context c,Libre3EmulatorConfig config) {
        Libre3EmulatorLog.i("Discovery","rediscover host="+(config.targetNode.isEmpty()?"local":config.targetNode));
        if(config.targetNode.isEmpty()) {
            if(Libre3Emulator.token==null) throw new IllegalStateException("Start Bluetooth first");
            Libre3Emulator.address=""; Libre3Emulator.addressObservedAt=0; askObserver(c,Libre3Emulator.token);
        } else if(!config.targetNode.equals("external")) {
            Libre3Emulator.remoteAddress=""; Libre3Emulator.remoteAddressObservedAt=0;
            Libre3Emulator.remoteDiscoveryStatus="Requesting watch address discovery";
            if(config.targetNode.equals(Libre3Emulator.remoteNode) && Libre3Emulator.remoteToken!=null && Libre3Emulator.remoteToken.length==8)
                observe(c,config.targetNode,Libre3Emulator.remoteToken,true);
            else send(c,config.targetNode,"query",new byte[0]);
        } else throw new IllegalStateException("Use Find another emulator on a second device to discover an independently managed host");
    }
    private static void observe(Context c,String node,byte[] token,boolean remoteHost) {
        String key=node+":"+Libre3EmulatorConfig.hex(token);
        if(scans.containsKey(key)) { Libre3EmulatorLog.i("Discovery","scan already active for node="+node); return; }
        Libre3EmulatorLog.i("Discovery","observe node="+node+" remoteHost="+remoteHost+" token="+Libre3EmulatorConfig.hex(token));
        if(remoteHost) Libre3Emulator.remoteDiscoveryStatus="Scanning for the watch advertising token";
        Libre3EmulatorAddress scan=new Libre3EmulatorAddress(c,token,new Libre3EmulatorAddress.Listener() {
            public void found(String address,byte[] observed) {
                scans.remove(key);
                if(remoteHost) {
                    if(node.equals(Libre3Emulator.remoteNode) && Arrays.equals(observed,Libre3Emulator.remoteToken)) {
                        Libre3Emulator.remoteAddress=address; Libre3Emulator.remoteAddressObservedAt=android.os.SystemClock.elapsedRealtime();
                        Libre3Emulator.remoteDiscoveryStatus="Watch advertising address observed";
                        Libre3EmulatorLog.i("Discovery","watch address="+address+" NFC="+Libre3Emulator.nfcAddress);
                    }
                }
                else try {
                    ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(b);
                    out.write(observed); out.writeUTF(address); send(c,node,"observed",b.toByteArray());
                } catch(IOException impossible) { throw new IllegalStateException(impossible); }
            }
            public void error(String message) {
                scans.remove(key);
                if(remoteHost) {
                    if(node.equals(Libre3Emulator.remoteNode) && Arrays.equals(token,Libre3Emulator.remoteToken)) Libre3Emulator.remoteDiscoveryStatus=message;
                }
                else send(c,node,"observer-error",message.getBytes(StandardCharsets.UTF_8));
            }
        });
        scans.put(key,scan); scan.start();
    }
}
