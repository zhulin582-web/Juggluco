/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.*;
import java.util.*;

/** An unbonded observer can obtain an address without Android substituting a bonded identity. */
@SuppressLint("MissingPermission")
final class Libre3EmulatorAddress implements AutoCloseable {
    interface Listener { void found(String address,byte[] token); void error(String message); }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Context context;
    private BluetoothLeScanner scanner;
    private final byte[] token;
    private final Listener listener;
    private final String log=Libre3EmulatorLog.scope("Scan");
    private final Set<String> candidates=new HashSet<>();
    private int results,withoutToken,wrongToken;
    private long started;
    private boolean closed;
    private final Runnable timeout=()->finishError("No matching emulator advertisement found in 20 seconds; results="+results+
        ", Libre 3 without token="+withoutToken+", other emulator tokens="+wrongToken);
    Libre3EmulatorAddress(Context context,byte[] token,Listener listener) {
        this.context=context.getApplicationContext(); this.token=token==null?null:token.clone(); this.listener=listener;
    }
    void start() {
        if(closed) return;
        started=SystemClock.elapsedRealtime();
        try {
            String permission=Build.VERSION.SDK_INT>=31?android.Manifest.permission.BLUETOOTH_SCAN:android.Manifest.permission.ACCESS_FINE_LOCATION;
            boolean allowed=context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
            boolean connect=Build.VERSION.SDK_INT<31 || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
            LocationManager location=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
            boolean locationOn=location!=null && (Build.VERSION.SDK_INT>=28?location.isLocationEnabled():
                location.isProviderEnabled(LocationManager.GPS_PROVIDER)||location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            Libre3EmulatorLog.i(log,"start sdk="+Build.VERSION.SDK_INT+" scanPermission="+allowed+" connectPermission="+connect+
                " locationEnabled="+locationOn+" expectedToken="+(token==null?"any emulator":Libre3EmulatorConfig.hex(token)));
            if(!allowed || !connect) { finishError("Open the emulator screen on this scanning device and allow Bluetooth/location permissions"); return; }
            if(Build.VERSION.SDK_INT<31 && !locationOn) { finishError("Enable Location on this scanning device for Bluetooth discovery"); return; }
            BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            scanner=adapter==null?null:adapter.getBluetoothLeScanner();
            if(scanner==null) { finishError("Bluetooth scanning is unavailable; check that Bluetooth is on"); return; }
            // A short software-filtered scan also accepts scan-response-only
            // callbacks and reveals whether any radio results are arriving.
            scanner.startScan(Collections.emptyList(),new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),callback);
            Libre3EmulatorLog.i(log,"scan submitted; software UUID/token match; timeout=20000ms");
            main.postDelayed(timeout,20000);
        } catch(Exception e) { Libre3EmulatorLog.error(log,"scan start failed",e); finishError(e.toString()); }
    }
    private final ScanCallback callback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) {
            main.post(()->accept(result));
        }
        @Override public void onBatchScanResults(List<ScanResult> results) { for(ScanResult r:results) onScanResult(0,r); }
        @Override public void onScanFailed(int code) { main.post(()->finishError("Bluetooth scan failed: "+code+
            (code==6?" (scanning too frequently; wait 30 seconds before retrying)":""))); }
    };
    private void accept(ScanResult result) {
        if(closed) return;
        results++;
        if(results==1) Libre3EmulatorLog.i(log,"first radio result after "+(SystemClock.elapsedRealtime()-started)+"ms");
        ScanRecord record=result.getScanRecord(); if(record==null) return;
        ParcelUuid service=new ParcelUuid(Libre3EmulatorProtocol.DATA);
        byte[] received=record.getServiceData(service);
        boolean libre=record.getServiceUuids()!=null && record.getServiceUuids().contains(service);
        if(!libre && received==null) return;
        try {
            String address=result.getDevice().getAddress();
            boolean matching=received!=null && received.length==8 && (token==null || Arrays.equals(token,received));
            if(received==null || received.length!=8) withoutToken++; else if(!matching) wrongToken++;
            String key=address+":"+(received==null?-1:received.length)+":"+matching;
            if(candidates.size()<32 && candidates.add(key)) Libre3EmulatorLog.i(log,"candidate address="+address+
                " rssi="+result.getRssi()+" connectable="+result.isConnectable()+" legacy="+result.isLegacy()+
                " tokenBytes="+(received==null?0:received.length)+" tokenMatch="+matching);
            if(!matching) return;
            // A token identifies the advertising instance, but does not make
            // getAddress() an on-air address. Android can replace a bonded
            // advertiser's RPA with the address saved when it was paired.
            int bondState=result.getDevice().getBondState();
            Libre3EmulatorLog.i(log,"matched device address="+address+" bondState="+bondState);
            if(bondState!=BluetoothDevice.BOND_NONE) {
                finishError("Emulator found, but this scanner is paired or pairing with its host. Android may return the saved " +
                    "identity address ("+address+") instead of its current BLE advertising address. " +
                    "Use Find another emulator on a device not paired with the host, preferably the reader phone.");
                return;
            }
            Libre3EmulatorLog.i(log,"matched address="+address+" token="+Libre3EmulatorConfig.hex(received));
            close(); listener.found(address,received.clone());
        } catch(Exception e) { Libre3EmulatorLog.error(log,"scan result failed",e); finishError(e.toString()); }
    }
    private void finishError(String error) { if(!closed) { Libre3EmulatorLog.w(log,error); close(); listener.error(error); } }
    @Override public void close() {
        if(closed) return; closed=true; main.removeCallbacks(timeout);
        Libre3EmulatorLog.i(log,"stop elapsedMs="+(SystemClock.elapsedRealtime()-started)+" results="+results+
            " withoutToken="+withoutToken+" wrongToken="+wrongToken);
        if(scanner!=null) try { scanner.stopScan(callback); } catch(Exception e) { Libre3EmulatorLog.error(log,"stopScan failed",e); }
    }
    /** Optional helper on the reader phone. Reports its observation to the host. */
    static Reporter report(Context context,String address,byte[] token,Listener listener) {
        Reporter report=new Reporter(context,address,token,listener); report.start(); return report;
    }
    static final class Reporter extends BluetoothGattCallback implements AutoCloseable {
        final Context context; final String address; final byte[] token; final Listener listener;
        final String log=Libre3EmulatorLog.scope("Report");
        final Handler main=new Handler(Looper.getMainLooper()); BluetoothGatt gatt; boolean done;
        long started,phaseStarted; String phase="starting";
        final Runnable timeout;
        Reporter(Context c,String a,byte[] t,Listener l) {
            context=c.getApplicationContext(); address=a; token=t.clone(); listener=l;
            timeout=()->finish("Address report timed out while "+phase+" for "+address);
        }
        void start() {
            try {
                started=SystemClock.elapsedRealtime();
                Libre3EmulatorLog.i(log,"connect address="+address+" token="+Libre3EmulatorConfig.hex(token));
                BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothDevice device=manager.getAdapter().getRemoteDevice(address);
                // The controller may be busy with another queued connection.
                // Once connected, give discovery and the write their own time.
                arm("connecting",60000);
                gatt=device.connectGatt(context,false,this,BluetoothDevice.TRANSPORT_LE);
                if(gatt==null) { finish("Cannot connect to the emulator helper"); return; }
            } catch(Exception e) { Libre3EmulatorLog.error(log,"report start failed",e); finish(e.toString()); }
        }
        private void arm(String next,long milliseconds) {
            main.removeCallbacks(timeout); phase=next; phaseStarted=SystemClock.elapsedRealtime();
            Libre3EmulatorLog.i(log,"phase="+phase+" timeoutMs="+milliseconds+" elapsedMs="+(phaseStarted-started));
            main.postDelayed(timeout,milliseconds);
        }
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) { main.post(()->{
            if(done) return;
            Libre3EmulatorLog.i(log,"connection status="+status+" state="+state);
            if(status!=0 || state==BluetoothProfile.STATE_DISCONNECTED) { finish("Address-report connection closed: "+status); return; }
            if(state==BluetoothProfile.STATE_CONNECTED) {
                arm("discovering services",20000);
                boolean submitted=g.discoverServices(); Libre3EmulatorLog.i(log,"discoverServices submitted="+submitted);
                if(!submitted) finish("Cannot discover emulator services");
            }
        }); }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) { main.post(()->{
            if(done) return;
            BluetoothGattService s=g.getService(Libre3EmulatorProtocol.SECURITY);
            BluetoothGattCharacteristic c=s==null?null:s.getCharacteristic(Libre3EmulatorGatt.PROBE);
            Libre3EmulatorLog.i(log,"services status="+status+" probePresent="+(c!=null));
            if(status!=0 || c==null) { finish("Emulator address-report service is missing"); return; }
            byte[] value=new byte[14]; System.arraycopy(token,0,value,0,8);
            for(int i=0;i<6;i++) value[8+i]=(byte)Integer.parseInt(address.substring(3*i,3*i+2),16);
            c.setValue(value); c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            arm("writing the address",10000);
            boolean submitted=g.writeCharacteristic(c); Libre3EmulatorLog.i(log,"probe write bytes=14 submitted="+submitted);
            if(!submitted) finish("Cannot write the observed address");
        }); }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) { main.post(()->{
            Libre3EmulatorLog.i(log,"probe write completed uuid="+c.getUuid()+" status="+status);
            finish(status==0?null:"Address report failed: "+status);
        }); }
        void finish(String error) {
            finish(error,true);
        }
        private void finish(String error,boolean notifyListener) {
            if(done) return; done=true; main.removeCallbacks(timeout);
            Libre3EmulatorLog.i(log,"finished address="+address+" phase="+phase+
                " elapsedMs="+(SystemClock.elapsedRealtime()-started)+" phaseMs="+(SystemClock.elapsedRealtime()-phaseStarted)+
                " result="+(error==null?"reported to host":error));
            if(gatt!=null) { try { gatt.disconnect(); } catch(Exception ignored) { } gatt.close(); }
            if(notifyListener) { if(error==null) listener.found(address,token); else listener.error(error); }
        }
        @Override public void close() { finish("Address report cancelled",false); }
    }
}
