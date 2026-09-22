/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import static tk.glucodata.Libre3EmulatorProtocol.*;

/** One peripheral, one authenticated central. Never shares a client's native context. */
@SuppressLint("MissingPermission")
final class Libre3EmulatorGatt implements AutoCloseable {
    static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final UUID PROBE=uuid("08982f01");
    interface Listener {
        void state(String state);
        void advertised(byte[] token);
        void address(String address);
        void failed(String message);
    }
    private final Context context;
    private final Libre3EmulatorConfig config;
    private final Store store;
    private final Source source;
    private final Listener listener;
    private final String log=Libre3EmulatorLog.scope("Gatt");
    private final HandlerThread thread=new HandlerThread("Libre3EmulatorGatt");
    private final Handler work;
    private final SecureRandom random=new SecureRandom();
    private final byte[] token=new byte[8];
    private final Map<UUID,BluetoothGattCharacteristic> characteristics=new HashMap<>();
    private final Map<UUID,byte[]> descriptors=new HashMap<>();
    private final ArrayDeque<BluetoothGattService> services=new ArrayDeque<>();
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothDevice central;
    private Libre3EmulatorProtocol protocol;
    private boolean notifying,closed;
    private AdvertisingSet advertisingSet;
    private UUID inFlight;
    private long writes,submitted,sent,lastActivity=SystemClock.elapsedRealtime();
    private final Runnable heartbeat=new Runnable() {
        public void run() {
            if(closed) return;
            info("heartbeat advertisingStarted="+(advertisingSet!=null)+" central="+central+" writes="+writes+
                " notifications="+sent+"/"+submitted+" inFlight="+inFlight+" idleMs="+(SystemClock.elapsedRealtime()-lastActivity)+
                " "+(protocol==null?"no protocol session":protocol.snapshot()));
            work.postDelayed(this,30000);
        }
    };
    private final Runnable timeout=()->disconnect("Notification completion timeout");
    private final Runnable loginTimeout=()->{
        if(protocol!=null && !protocol.authorized()) disconnect("Authentication timeout");
    };
    private final Runnable tick=new Runnable() {
        public void run() {
            if(closed) return;
            try { if(protocol!=null) { protocol.tick(); pump(); } }
            catch(Exception e) { failedOperation("tick",e); }
            work.postDelayed(this,config.intervalSeconds*1000L);
        }
    };
    Libre3EmulatorGatt(Context context,Libre3EmulatorConfig config,Store store,Source source,Listener listener) {
        this.context=context.getApplicationContext(); this.config=config.copy(); this.store=store;
        this.source=source; this.listener=listener; random.nextBytes(token); thread.start(); work=new Handler(thread.getLooper());
    }
    private void info(String message) { Libre3EmulatorLog.i(log,message); }
    private void state(String message) { info(message); listener.state(message); }
    private void failedOperation(String operation,Exception error) {
        Libre3EmulatorLog.error(log,operation+" failed; "+(protocol==null?"no protocol":protocol.snapshot()),error);
        disconnect(operation+": "+error.getMessage());
    }
    void start() { work.post(()->{
        try {
            info("start sdk="+Build.VERSION.SDK_INT+" model="+Build.MANUFACTURER+"/"+Build.MODEL+" "+Libre3EmulatorLog.profile(config));
            BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is off");
            advertiser=adapter.getBluetoothLeAdvertiser();
            info("adapter state="+adapter.getState()+" multipleAdvertisements="+adapter.isMultipleAdvertisementSupported()+
                " extendedAdvertisements="+adapter.isLeExtendedAdvertisingSupported()+" advertiser="+(advertiser!=null));
            if(advertiser==null) throw new IllegalStateException("BLE advertising is unavailable");
            server=manager.openGattServer(context,callback);
            if(server==null) throw new IllegalStateException("Cannot open the GATT server");
            info("GATT server opened"); makeServices(); addNextService();
            if(config.glucoseMode!=2) work.post(tick);
            work.postDelayed(heartbeat,30000);
        } catch(Exception e) { Libre3EmulatorLog.error(log,"start failed",e); listener.failed(e.toString()); close(); }
    }); }
    void activate(long seconds) { work.post(()->{
        if(closed) return;
        long at=config.startTime!=0?config.startTime:(seconds>0?seconds:System.currentTimeMillis()/1000L);
        if(store.activation()==0) store.activation(at);
        if(protocol!=null) protocol.activate(seconds);
        state("Sensor activated requested="+seconds+" effective="+store.activation());
    }); }
    void glucoseAvailable(Reading reading) {
        if(config.glucoseMode!=2) return;
        work.post(()->{
            if(closed) return;
            try {
                int minute=sampleMinute(store.activation(),reading);
                if(minute>=config.warmupMinutes && minute<=config.wearMinutes) store.sample(minute,reading);
                if(protocol!=null) { protocol.sourceChanged(reading); pump(); }
            } catch(Exception e) { failedOperation("source reading",e); }
        });
    }
    private BluetoothGattCharacteristic characteristic(BluetoothGattService s,UUID id,int props) {
        BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(id,props,
            ((props&BluetoothGattCharacteristic.PROPERTY_READ)!=0?BluetoothGattCharacteristic.PERMISSION_READ:0)|
            ((props&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0?BluetoothGattCharacteristic.PERMISSION_WRITE:0));
        if((props&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0) {
            c.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ|BluetoothGattDescriptor.PERMISSION_WRITE));
        }
        s.addCharacteristic(c); characteristics.put(id,c); return c;
    }
    private void makeServices() {
        final int notify=BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE;
        final int write=BluetoothGattCharacteristic.PROPERTY_WRITE;
        BluetoothGattService security=new BluetoothGattService(SECURITY,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        characteristic(security,COMMAND,notify|write); characteristic(security,CERT,notify|write);
        characteristic(security,CHALLENGE,notify|write);
        characteristic(security,PROBE,write); // Optional helper: reports this advertising instance's observed address.
        services.add(security);
        BluetoothGattService data=new BluetoothGattService(DATA,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        characteristic(data,CONTROL,notify|write);
        for(UUID id:new UUID[]{STATUS,GLUCOSE,HISTORY,CLINICAL,EVENTS,FACTORY}) characteristic(data,id,notify);
        services.add(data);
        BluetoothGattService info=new BluetoothGattService(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),0);
        for(String[] row:new String[][]{{"2a29","Abbott"},{"2a25",config.serial},{"2a26","1.4.2.30"},{"2a24","Libre 3"}}) {
            BluetoothGattCharacteristic c=characteristic(info,UUID.fromString("0000"+row[0]+"-0000-1000-8000-00805f9b34fb"),BluetoothGattCharacteristic.PROPERTY_READ);
            c.setValue(row[1].getBytes(StandardCharsets.US_ASCII));
        }
        services.add(info);
    }
    private void addNextService() {
        if(services.isEmpty()) { advertise(); return; }
        BluetoothGattService next=services.remove(); info("register service="+next.getUuid());
        if(!server.addService(next)) throw new IllegalStateException("Cannot register GATT service "+next.getUuid());
    }
    private boolean claim(BluetoothDevice device) {
        if(central!=null) {
            if(!central.equals(device)) info("reject another reader="+device+" current="+central);
            return central.equals(device);
        }
        try {
            info("claim reader="+device+" savedAuthorization="+(store.authorization()!=null));
            protocol=new Libre3EmulatorProtocol(config,new Libre3EmulatorCrypto.Native(),store,source,new Diagnostic() {
                public void message(String message) { info(message); }
                public void state(String message) { Libre3EmulatorGatt.this.state(message); }
            },
                ()->System.currentTimeMillis()/1000L,random);
            central=device; descriptors.clear(); notifying=false;
            state("Client connected; waiting for authentication");
            work.postDelayed(loginTimeout,60000); return true;
        } catch(Exception e) { Libre3EmulatorLog.error(log,"claim failed",e); state(e.toString()); return false; }
    }
    private void advertise() {
        AdvertisingSetParameters.Builder params=new AdvertisingSetParameters.Builder()
            .setLegacyMode(true).setConnectable(true).setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM);
        // Privileged installations can request a stable public identity. Ordinary
        // installs keep Android's default and learn the real address from a peer.
        boolean privileged=context.checkSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)==PackageManager.PERMISSION_GRANTED;
        info("advertise legacy/connectable/scannable service="+DATA+" token="+Libre3EmulatorConfig.hex(token)+" privileged="+privileged);
        if(privileged) {
            try { params.getClass().getMethod("setOwnAddressType",int.class).invoke(params,0); info("requested public advertising address"); }
            catch(Exception error) { Libre3EmulatorLog.error(log,"public address request unavailable; peer observation required",error); }
        } else {
            info("Android chooses the advertising address; the address in Android settings is not a verified advertising address");
        }
        AdvertiseData advertisement=new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(DATA)).build();
        AdvertiseData scanResponse=new AdvertiseData.Builder().addServiceData(new ParcelUuid(DATA),token).build();
        advertiser.startAdvertisingSet(params.build(),advertisement,scanResponse,null,null,advertisingCallback,work);
        info("startAdvertisingSet submitted; awaiting callback");
    }
    private final AdvertisingSetCallback advertisingCallback=new AdvertisingSetCallback() {
        @Override public void onAdvertisingSetStarted(AdvertisingSet set,int txPower,int status) {
            info("onAdvertisingSetStarted status="+status+" txPower="+txPower);
            if(closed) { if(advertiser!=null) advertiser.stopAdvertisingSet(this); return; }
            if(status!=ADVERTISE_SUCCESS) { listener.failed("BLE advertising failed: "+status); close(); return; }
            advertisingSet=set; state("Advertising; waiting for a client"); listener.advertised(token.clone());
            if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)==PackageManager.PERMISSION_GRANTED) {
                try { set.getClass().getMethod("getOwnAddress").invoke(set); }
                catch(Exception error) { Libre3EmulatorLog.error(log,"getOwnAddress unavailable",error); }
            }
        }
        @Override public void onAdvertisingEnabled(AdvertisingSet set,boolean enabled,int status) { info("onAdvertisingEnabled enabled="+enabled+" status="+status); }
        @Override public void onAdvertisingSetStopped(AdvertisingSet set) { info("onAdvertisingSetStopped"); }
        // System API callback: intentionally no @Override for public-SDK builds.
        public void onOwnAddressRead(AdvertisingSet set,int type,String address) {
            info("own advertising address="+address+" type="+type);
            if(!closed && BluetoothAdapter.checkBluetoothAddress(address)) listener.address(address);
        }
    };
    private final BluetoothGattServerCallback callback=new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status,BluetoothGattService service) { work.post(()->{
            if(closed) return;
            info("onServiceAdded uuid="+service.getUuid()+" status="+status);
            try { if(status!=BluetoothGatt.GATT_SUCCESS) throw new IllegalStateException("GATT service failed: "+status); addNextService(); }
            catch(Exception e) { Libre3EmulatorLog.error(log,"service registration failed",e); listener.failed(e.toString()); close(); }
        }); }
        @Override public void onConnectionStateChange(BluetoothDevice device,int status,int state) { work.post(()->{
            if(closed) return;
            info("connection device="+device+" status="+status+" state="+state+" claimed="+(central!=null && central.equals(device)));
            // Other GATT services may share this radio/connection (e.g. the
            // Wear link). Claim a reader only when it accesses our protocol.
            if(state==BluetoothProfile.STATE_DISCONNECTED && central!=null && central.equals(device))
                release("Disconnected ("+status+"); advertising");
        }); }
        @Override public void onCharacteristicReadRequest(BluetoothDevice d,int id,int offset,BluetoothGattCharacteristic c) { work.post(()->{
            if(closed) return;
            lastActivity=SystemClock.elapsedRealtime(); info("read device="+d+" id="+id+" characteristic="+label(c.getUuid())+" offset="+offset);
            if((c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)==0) {
                server.sendResponse(d,id,BluetoothGatt.GATT_READ_NOT_PERMITTED,offset,null); return;
            }
            byte[] value=c.getValue(); if(value==null) value=new byte[0];
            int result=offset<0||offset>value.length?BluetoothGatt.GATT_INVALID_OFFSET:BluetoothGatt.GATT_SUCCESS;
            server.sendResponse(d,id,result,offset,result==0?Arrays.copyOfRange(value,offset,value.length):null);
        }); }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice d,int id,BluetoothGattCharacteristic c,
                boolean prepared,boolean response,int offset,byte[] value) {
            byte[] copy=value.clone(); work.post(()->{
                if(closed) return;
                writes++; lastActivity=SystemClock.elapsedRealtime();
                info("write device="+d+" id="+id+" characteristic="+label(c.getUuid())+" bytes="+copy.length+
                    " offset="+offset+" prepared="+prepared+" response="+response);
                int result=prepared?BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:
                    offset!=0?BluetoothGatt.GATT_INVALID_OFFSET:central!=null&&!central.equals(d)?BluetoothGatt.GATT_FAILURE:BluetoothGatt.GATT_SUCCESS;
                if(result==0 && (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0) result=BluetoothGatt.GATT_WRITE_NOT_PERMITTED;
                if(result==0 && c.getUuid().equals(PROBE) && (copy.length!=14 ||
                    !Arrays.equals(token,Arrays.copyOf(copy,8)) || (protocol!=null && protocol.authorized()))) result=BluetoothGatt.GATT_FAILURE;
                if(result==0 && !c.getUuid().equals(PROBE) && !claim(d)) result=BluetoothGatt.GATT_FAILURE;
                if(response) info("write response status="+result+" submitted="+server.sendResponse(d,id,result,offset,null));
                else if(result!=0) info("write rejected status="+result);
                if(result!=0) return;
                try {
                    if(c.getUuid().equals(PROBE)) {
                        StringBuilder address=new StringBuilder();
                        for(int i=8;i<14;i++) { if(i>8) address.append(':'); address.append(String.format(Locale.ROOT,"%02X",copy[i]&255)); }
                        info("probe accepted observedAddress="+address+" token="+Libre3EmulatorConfig.hex(token));
                        listener.address(address.toString());
                    } else { protocol.write(c.getUuid(),copy); if(protocol.authorized()) work.removeCallbacks(loginTimeout); pump(); }
                } catch(Exception e) { failedOperation("write "+label(c.getUuid()),e); }
            });
        }
        @Override public void onDescriptorReadRequest(BluetoothDevice d,int id,int offset,BluetoothGattDescriptor descriptor) { work.post(()->{
            if(closed) return;
            info("descriptor read device="+d+" characteristic="+label(descriptor.getCharacteristic().getUuid())+" offset="+offset);
            byte[] value=d.equals(central)?descriptors.getOrDefault(descriptor.getCharacteristic().getUuid(),new byte[2]):new byte[2];
            server.sendResponse(d,id,offset<0||offset>2?BluetoothGatt.GATT_INVALID_OFFSET:0,offset,
                offset>=0&&offset<=2?Arrays.copyOfRange(value,offset,2):null);
        }); }
        @Override public void onDescriptorWriteRequest(BluetoothDevice d,int id,BluetoothGattDescriptor descriptor,
                boolean prepared,boolean response,int offset,byte[] value) {
            byte[] copy=value.clone(); work.post(()->{
                if(closed) return;
                lastActivity=SystemClock.elapsedRealtime();
                info("descriptor write device="+d+" characteristic="+label(descriptor.getCharacteristic().getUuid())+
                    " descriptor="+descriptor.getUuid()+" offset="+offset+" prepared="+prepared+" bytes="+copy.length+
                    " mode="+(copy.length==2?u16(copy,0):-1));
                int result=prepared?BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:offset!=0?BluetoothGatt.GATT_INVALID_OFFSET:
                    !CCCD.equals(descriptor.getUuid())||copy.length!=2||copy[1]!=0||(copy[0]&255)>2?
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:!claim(d)?BluetoothGatt.GATT_FAILURE:0;
                if(response) info("descriptor response status="+result+" submitted="+server.sendResponse(d,id,result,offset,null));
                else if(result!=0) info("descriptor rejected status="+result);
                if(result!=0) return;
                UUID c=descriptor.getCharacteristic().getUuid(); descriptors.put(c,copy);
                try { protocol.subscribe(c,copy[0]!=0); pump(); }
                catch(Exception e) { failedOperation("subscribe "+label(c),e); }
            });
        }
        @Override public void onExecuteWrite(BluetoothDevice d,int id,boolean execute) { work.post(()->{
            info("execute write unsupported device="+d+" execute="+execute);
            if(!closed) server.sendResponse(d,id,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null);
        }); }
        @Override public void onNotificationSent(BluetoothDevice d,int status) { work.post(()->{
            if(closed || central==null || !central.equals(d) || !notifying) return;
            lastActivity=SystemClock.elapsedRealtime(); sent++;
            if(status!=0 || sent<=32 || sent%64==0) info("notification completed characteristic="+inFlight+" status="+status+" count="+sent);
            work.removeCallbacks(timeout); notifying=false; inFlight=null;
            if(status!=0) { disconnect("Notification failed: "+status); return; }
            pump();
        }); }
        @Override public void onMtuChanged(BluetoothDevice device,int mtu) { work.post(()->info("MTU device="+device+" mtu="+mtu)); }
    };
    private void pump() {
        if(closed || notifying || central==null || protocol==null) return;
        try {
            Packet packet=protocol.poll();
            if(packet==null) { if(protocol.pending()) work.post(this::pump); return; }
            BluetoothGattCharacteristic c=characteristics.get(packet.characteristic);
            byte[] subscription=descriptors.get(packet.characteristic);
            if(subscription==null || subscription[0]==0) { info("drop unsubscribed notification "+label(packet.characteristic)); work.post(this::pump); return; }
            c.setValue(packet.value); notifying=true; inFlight=packet.characteristic; submitted++;
            if(submitted<=32 || submitted%64==0) info("notify "+label(packet.characteristic)+" bytes="+packet.value.length+
                " indication="+(subscription[0]==2)+" count="+submitted);
            if(!server.notifyCharacteristicChanged(central,c,subscription[0]==2)) { notifying=false; disconnect("Cannot submit notification"); return; }
            work.postDelayed(timeout,10000);
        } catch(Exception e) { failedOperation("notification pump",e); }
    }
    private void disconnect(String reason) {
        Libre3EmulatorLog.w(log,"disconnect reason="+reason+" inFlight="+inFlight+" "+(protocol==null?"no protocol":protocol.snapshot()));
        BluetoothDevice old=central; release(reason);
        if(server!=null && old!=null) server.cancelConnection(old);
    }
    private void release(String reason) {
        info("release central="+central+" reason="+reason+" writes="+writes+" notifications="+sent+"/"+submitted);
        work.removeCallbacks(timeout); work.removeCallbacks(loginTimeout); notifying=false; central=null; descriptors.clear();
        inFlight=null;
        if(protocol!=null) { protocol.close(); protocol=null; } state(reason);
    }
    @Override public void close() { work.post(()->{
        if(closed) return; info("close GATT server and advertising set"); closed=true; work.removeCallbacksAndMessages(null);
        if(advertiser!=null) try { advertiser.stopAdvertisingSet(advertisingCallback); } catch(Exception ignored) { }
        if(server!=null) { if(central!=null) try { server.cancelConnection(central); } catch(Exception ignored) { } server.close(); server=null; }
        release("Stopped"); thread.quitSafely();
    }); }
}
