/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import android.content.*;
import android.os.*;
import android.util.Base64;
import java.util.*;
import java.io.*;

/** Application integration, profiles and the single active NFC USB controller. */
public final class Libre3Emulator {
    static final String PREFS="libre3_emulator";
    static volatile String status="Stopped",address="",remoteStatus="",remoteAddress="",nfcStatus="NFC stopped";
    static volatile String discoveryStatus="",remoteDiscoveryStatus="",nfcAddress="";
    static volatile long addressObservedAt,remoteAddressObservedAt;
    static volatile byte[] token,remoteToken;
    static volatile Libre3EmulatorConfig running;
    static volatile boolean remoteReady;
    static volatile String remoteProfile="",remoteNode="";
    static Libre3NfcEmulator nfc;
    private static Runnable restartNfc;
    static final Handler MAIN=new Handler(Looper.getMainLooper());
    private Libre3Emulator() { }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    static String base64(byte[] b) { return Base64.encodeToString(b,Base64.NO_WRAP); }
    static byte[] bytes(String s) { return s==null?null:Base64.decode(s,Base64.DEFAULT); }
    public static void show(Context c) { c.startActivity(new Intent(c,Libre3EmulatorActivity.class).addFlags(c instanceof android.app.Activity?0:Intent.FLAG_ACTIVITY_NEW_TASK)); }
    static void save(Context c,Libre3EmulatorConfig config) {
        prefs(c).edit().putString("profile:"+config.name,base64(config.encode())).putString("selected",config.name).apply();
    }
    static Libre3EmulatorConfig load(Context c,String name) {
        String encoded=prefs(c).getString("profile:"+name,null);
        return encoded==null?new Libre3EmulatorConfig():Libre3EmulatorConfig.decode(bytes(encoded));
    }
    static List<String> profiles(Context c) {
        List<String> result=new ArrayList<>();
        for(String key:prefs(c).getAll().keySet()) if(key.startsWith("profile:")) result.add(key.substring(8));
        Collections.sort(result); return result;
    }
    public static void captureScan(byte[] first,byte[] second) {
        try {
            Libre3NfcProfile p=Libre3NfcProfile.fromScan(first,second);
            prefs(Applic.app).edit().putString("real:"+p.serial(),base64(p.usbPayload())).putString("lastReal",p.serial()).apply();
        } catch(Exception ignored) { /* Unsupported variants do not affect real-sensor scanning. */ }
    }
    public static void captureCertificate(String serial,byte[] certificate) {
        if(serial==null || certificate==null || certificate.length!=140) return;
        String shortId=serial.length()>9?serial.substring(serial.length()-9):serial;
        prefs(Applic.app).edit().putString("certificate:"+shortId,base64(certificate)).apply();
    }
    static Libre3EmulatorConfig realSensor(Context context,Libre3EmulatorConfig base) {
        String serial=Natives.lastsensorname();
        if(serial==null || serial.length()<9) throw new IllegalStateException("No real Libre 3 sensor available");
        long ptr=Natives.getdataptr(serial);
        if(ptr==0) throw new IllegalStateException("Cannot open the real sensor");
        Libre3EmulatorConfig c=base.copy();
        try {
            long sensor=Natives.getsensorptr(ptr);
            if(sensor==0) throw new IllegalStateException("Cannot open the real sensor data");
            if(Natives.getSensorptrLibreVersion(sensor)!=3) throw new IllegalStateException("The latest sensor is not Libre 3");
            c.serial=serial.substring(serial.length()-9); c.startTime=Natives.getSensorStartmsec(ptr)/1000L;
            byte[] pin=Natives.getpin(sensor);
            if(pin==null || pin.length!=4) throw new IllegalStateException("The real sensor PIN is unavailable");
            c.pin=Libre3EmulatorProtocol.u32(pin,0);
            String stored=prefs(context).getString("real:"+c.serial,null);
            if(stored!=null) {
                Libre3NfcProfile real=Libre3NfcProfile.fromUsbPayload(bytes(stored));
                c.warmupMinutes=real.warmupMinutes(); c.wearMinutes=real.wearMinutes();
            }
            String cert=prefs(context).getString("certificate:"+c.serial,null);
            if(cert!=null) c.certificate=bytes(cert);
            c.unused=false; c.patchState=4; return c;
        } finally { Natives.freedataptr(ptr); }
    }
    static void start(Context c,Libre3EmulatorConfig config) {
        Libre3EmulatorLog.i("Control","start Bluetooth "+Libre3EmulatorLog.profile(config));
        if(nfc!=null) throw new IllegalStateException("Stop NFC before changing the Bluetooth session");
        if(config.startTime==0) config.startTime=System.currentTimeMillis()/1000L;
        config.validate(); save(c,config);
        if(config.targetNode.equals("external")) return;
        if(config.targetNode.isEmpty()) {
            if(running!=null) {
                if(!running.profileId().equals(config.profileId())) throw new IllegalStateException("Stop Bluetooth before changing its settings");
                return;
            }
            c.startForegroundService(new Intent(c,Libre3EmulatorService.class).setAction("start").putExtra("config",config.encode()));
        } else {
            remoteReady=false; remoteAddress=""; remoteToken=null; remoteProfile=""; remoteNode=config.targetNode;
            remoteDiscoveryStatus=""; remoteAddressObservedAt=0; remoteStatus="Starting on watch";
            Libre3EmulatorWear.send(c,config.targetNode,"start",config.encode());
            MAIN.postDelayed(()->{
                if(!remoteReady && remoteStatus.equals("Starting on watch")) {
                    remoteStatus="No reply from watch. Open its emulator screen, allow Bluetooth permissions, then retry.";
                    Libre3EmulatorLog.w("Control",remoteStatus);
                }
            },20000);
        }
    }
    static void stop(Context c,Libre3EmulatorConfig config) {
        Libre3EmulatorLog.i("Control","stop Bluetooth host="+config.targetNode);
        stopNfc();
        if(config.targetNode.isEmpty()) c.stopService(new Intent(c,Libre3EmulatorService.class));
        else if(!config.targetNode.equals("external")) Libre3EmulatorWear.send(c,config.targetNode,"stop",new byte[0]);
    }
    static String currentAddress(Libre3EmulatorConfig config) {
        if(config.targetNode.isEmpty()) return address;
        if(config.targetNode.equals("external")) return config.address;
        return config.targetNode.equals(remoteNode)?remoteAddress:"";
    }
    static void observedAddress(String observed,String source) {
        Libre3EmulatorLog.i("Address","observed="+observed+" previous="+address+" source="+source+" NFC="+nfcAddress);
        address=observed; addressObservedAt=SystemClock.elapsedRealtime(); discoveryStatus="Address observed via "+source;
        if(!nfcAddress.isEmpty() && !nfcAddress.equalsIgnoreCase(observed))
            Libre3EmulatorLog.w("Address","Active NFC address differs from observed advertising address; stop NFC and start it again with the discovered address");
    }
    static String currentDiscovery(Libre3EmulatorConfig config) {
        if(config.targetNode.isEmpty()) return discoveryStatus;
        return config.targetNode.equals(remoteNode)?remoteDiscoveryStatus:"";
    }
    static long addressAgeSeconds(Libre3EmulatorConfig config) {
        long at=config.targetNode.isEmpty()?addressObservedAt:config.targetNode.equals(remoteNode)?remoteAddressObservedAt:0;
        return at==0?-1:Math.max(0,(SystemClock.elapsedRealtime()-at)/1000);
    }
    static String currentStatus(Libre3EmulatorConfig config) {
        if(config.targetNode.isEmpty()) return status;
        if(config.targetNode.equals("external")) return "External Bluetooth host";
        return config.targetNode.equals(remoteNode)?remoteStatus:"Select the profile and query or start the watch";
    }
    static void startNfc(Context context,Libre3EmulatorConfig config,boolean useEnteredAddress) {
        Libre3EmulatorLog.i("NFC","start requested manual="+useEnteredAddress+" "+Libre3EmulatorLog.profile(config));
        if(nfc!=null) throw new IllegalStateException("Stop the current NFC emulator first");
        boolean external=config.targetNode.equals("external");
        if(!external) {
            boolean ready=config.targetNode.isEmpty()?running!=null && token!=null && running.profileId().equals(config.profileId()):
                remoteReady && remoteNode.equals(config.targetNode) && remoteProfile.equals(config.profileId());
            if(!ready) throw new IllegalStateException("Start Bluetooth with this profile before starting NFC");
        }
        String resolved=useEnteredAddress||external?config.address:currentAddress(config);
        if(resolved==null || resolved.isEmpty()) throw new IllegalStateException("Discover the advertised Bluetooth address first");
        String observed=currentAddress(config);
        Libre3EmulatorLog.i("NFC","resolved address="+resolved+" source="+(external?"external":useEnteredAddress?"entered":"observed")+
            " observed="+observed+" observationAgeSeconds="+addressAgeSeconds(config));
        if(!external && (observed.isEmpty() || !resolved.equalsIgnoreCase(observed)))
            Libre3EmulatorLog.w("NFC","NFC address is "+(observed.isEmpty()?"unverified":"different from the observed advertising address")+
                "; an entered address does not change the radio address");
        Store store=new Store(context,config);
        Libre3EmulatorConfig nfcConfig=config.copy();
        if(config.unused && store.activation()!=0) nfcConfig.unused=false;
        Libre3NfcProfile profile=nfcConfig.nfc(resolved);
        nfcStatus="Starting NFC"; nfcAddress=resolved;
        try { nfc=Libre3NfcEmulator.start(context,profile,new Libre3NfcEmulator.Listener() {
            public void onReady() { nfcStatus="NFC ready: "+resolved; Libre3EmulatorLog.i("NFC",nfcStatus); }
            public void onStopped() {
                nfc=null; nfcStatus="NFC stopped"; nfcAddress=""; Libre3EmulatorLog.i("NFC",nfcStatus);
                Runnable next=restartNfc; restartNfc=null;
                if(next!=null) next.run();
            }
            public void onError(Exception error) {
                nfc=null; restartNfc=null; nfcAddress=""; nfcStatus="NFC: "+error.getMessage();
                Libre3EmulatorLog.error("NFC","controller failed",error);
            }
            public void onConnectionScan(boolean activation,long requestedStart) {
                Libre3EmulatorLog.i("NFC","connection scan activation="+activation+" requestedStart="+requestedStart+
                    " storedActivation="+store.activation()+" address="+resolved);
                if(!activation || !config.unused || store.activation()!=0) return;
                store.activation(config.startTime!=0?config.startTime:requestedStart);
                if(config.targetNode.isEmpty()) {
                    Libre3EmulatorService service=Libre3EmulatorService.instance;
                    if(service!=null) service.activate(requestedStart);
                } else if(!external) Libre3EmulatorWear.activate(context,config.targetNode,requestedStart);
                // The PM3 caches replies. Reload after the activation exchange
                // so a subsequent scan sees the configured running state.
                restartNfc=()->{
                    try { startNfc(context,config,useEnteredAddress); }
                    catch(Exception error) { nfcStatus=error.getMessage(); Libre3EmulatorLog.error("NFC","restart after activation failed",error); }
                };
                MAIN.postDelayed(()->{ if(restartNfc!=null && nfc!=null) nfc.close(); },1000);
            }
        }); } catch(Exception error) {
            nfcAddress=""; nfcStatus="NFC: "+error.getMessage();
            Libre3EmulatorLog.error("NFC","start failed",error); throw error;
        }
    }
    static void stopNfc() { Libre3EmulatorLog.i("NFC","stop requested active="+(nfc!=null)); restartNfc=null; if(nfc!=null) nfc.close(); }
    static final class Store implements Libre3EmulatorProtocol.Store {
        final SharedPreferences prefs; final String key;
        final File samples;
        private Map<Integer,Libre3EmulatorProtocol.Reading> cache;
        Store(Context c,Libre3EmulatorConfig config) {
            prefs=Libre3Emulator.prefs(c); key=config.authorizationId();
            samples=new File(c.getFilesDir(),"libre3-emulator-"+key+".samples");
        }
        public byte[] authorization() { return bytes(prefs.getString("auth:"+key,null)); }
        public void authorization(byte[] record) { prefs.edit().putString("auth:"+key,base64(record)).commit(); }
        public long activation() { return prefs.getLong("activation:"+key,0); }
        public void activation(long time) { prefs.edit().putLong("activation:"+key,time).commit(); }
        public synchronized Libre3EmulatorProtocol.Reading sample(int minute) {
            if(cache==null) {
                cache=new HashMap<>();
                if(samples.exists()) try(RandomAccessFile file=new RandomAccessFile(samples,"r")) {
                    int count=(int)Math.min(32768,file.length()/12);
                    for(int at=0;at<count;at++) {
                        long time=file.readLong(); int glucose=file.readUnsignedShort(),rate=file.readShort();
                        if(time!=0) cache.put(at,new Libre3EmulatorProtocol.Reading(time,glucose,rate));
                    }
                } catch(IOException e) { throw new IllegalStateException("Cannot read emulator samples",e); }
            }
            return cache.get(minute);
        }
        public synchronized void sample(int minute,Libre3EmulatorProtocol.Reading reading) {
            if(minute<0 || minute>32767) return;
            Libre3EmulatorProtocol.Reading old=sample(minute);
            if(old!=null && old.time>reading.time) return;
            if(old!=null && old.time==reading.time && old.glucose==reading.glucose && old.rate==reading.rate) return;
            try(RandomAccessFile file=new RandomAccessFile(samples,"rw")) {
                file.seek(minute*12L); file.writeLong(reading.time); file.writeShort(reading.glucose); file.writeShort(reading.rate);
                cache.put(minute,reading);
            } catch(IOException e) { throw new IllegalStateException("Cannot save emulator samples",e); }
        }
        void reset() {
            prefs.edit().remove("auth:"+key).remove("activation:"+key).commit();
            if(samples.exists() && !samples.delete()) throw new IllegalStateException("Cannot reset emulator samples");
            cache=null;
        }
    }
    static Libre3EmulatorProtocol.Reading latest() {
        long[] value=Natives.getlastGlucose();
        if(value==null || value.length!=2) return null;
        int glucose=(int)((value[1]&0xffffffffL)/10),rate=(short)(value[1]>>>32)/10;
        return new Libre3EmulatorProtocol.Reading(value[0],glucose,rate);
    }
    static Libre3EmulatorProtocol.Source source() {
        return new Libre3EmulatorProtocol.Source() {
            public Libre3EmulatorProtocol.Reading latest() { return Libre3Emulator.latest(); }
            public Libre3EmulatorProtocol.Reading[] history(long from,long through) {
                long[] data=Natives.libre3EmulatorHistory(from,through);
                if(data==null) return new Libre3EmulatorProtocol.Reading[0];
                Libre3EmulatorProtocol.Reading[] result=new Libre3EmulatorProtocol.Reading[data.length/2];
                for(int i=0;i<result.length;i++)
                    result[i]=new Libre3EmulatorProtocol.Reading(data[2*i],(int)(data[2*i+1]/10),0);
                return result;
            }
        };
    }
}
