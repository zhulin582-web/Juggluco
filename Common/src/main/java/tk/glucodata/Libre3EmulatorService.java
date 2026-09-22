/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import android.app.*;
import android.content.*;
import android.os.*;

/** Explicitly started peripheral; foreground notification offers a stop action. */
public final class Libre3EmulatorService extends Service {
    static volatile Libre3EmulatorService instance;
    private volatile Libre3EmulatorGatt gatt;
    private PowerManager.WakeLock wake;
    private String owner="";
    private volatile boolean destroyed;
    private volatile boolean failed;
    private static final int NOTIFICATION=0x4c3345;
    private final String log=Libre3EmulatorLog.scope("Service");
    @Override public void onCreate() { super.onCreate(); instance=this; Libre3EmulatorLog.i(log,"created diagnostics=2026-09-22-live-backfill"); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        Libre3EmulatorLog.i(log,"startCommand action="+(intent==null?"restart":intent.getAction())+" id="+id+" flags="+flags+" alreadyRunning="+(gatt!=null));
        if(intent!=null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if(gatt!=null) { publish(); return START_STICKY; }
        try {
            byte[] data=intent==null?Libre3Emulator.bytes(Libre3Emulator.prefs(this).getString("activeConfig",null)):intent.getByteArrayExtra("config");
            if(data==null) { Libre3EmulatorLog.w(log,"no configuration; stopping"); stopSelf(); return START_NOT_STICKY; }
            if(BuildConfig.libreVersion!=3 || Build.VERSION.SDK_INT<26) throw new IllegalStateException("Libre 3 and Android 8 or newer are required");
            Libre3EmulatorConfig config=Libre3EmulatorConfig.decode(data);
            owner=intent==null?Libre3Emulator.prefs(this).getString("owner",""):intent.getStringExtra("owner");
            if(owner==null) owner="";
            Libre3EmulatorLog.i(log,"owner="+(owner.isEmpty()?"local":owner)+" "+Libre3EmulatorLog.profile(config));
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("libre3_emulator",getString(R.string.l3emu_title),NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,Libre3EmulatorActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,NOTIFICATION,new Intent(this,Libre3EmulatorService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,"libre3_emulator").setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(getString(R.string.l3emu_title)).setContentText(config.serial).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,getString(R.string.l3emu_stop),stop).build()).setOngoing(true).build();
            if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION,notification);
            if(wake==null) {
                wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Juggluco:Libre3Emulator");
                wake.setReferenceCounted(false); wake.acquire();
            }
            Libre3Emulator.running=config; Libre3Emulator.address=""; Libre3Emulator.token=null;
            Libre3Emulator.addressObservedAt=0; Libre3Emulator.discoveryStatus="Waiting for advertising to start";
            Libre3Emulator.prefs(this).edit().putString("activeConfig",Libre3Emulator.base64(data)).putString("owner",owner).apply();
            Libre3Emulator.Store store=new Libre3Emulator.Store(this,config);
            if(!config.unused && store.activation()==0) store.activation(config.startTime);
            gatt=new Libre3EmulatorGatt(this,config,store,Libre3Emulator.source(),
                new Libre3EmulatorGatt.Listener() {
                    public void state(String state) { if(!destroyed && !failed) { Libre3Emulator.status=state; publish(); } }
                    public void advertised(byte[] token) {
                        if(destroyed) return;
                        Libre3Emulator.token=token; publish();
                        if(owner.isEmpty()) Libre3EmulatorWear.askObserver(Libre3EmulatorService.this,token);
                    }
                    public void address(String address) { if(!destroyed) { Libre3Emulator.observedAddress(address,"Bluetooth callback/report"); publish(); } }
                    public void failed(String message) { if(!destroyed) {
                        Libre3EmulatorLog.w(log,"failed: "+message); failed=true; Libre3Emulator.status=message; publish(); stopSelf();
                    } }
                });
            gatt.start(); return START_STICKY;
        } catch(Exception error) {
            Libre3EmulatorLog.error(log,"foreground service startup failed",error);
            failed=true; Libre3Emulator.status=error.toString(); publish(); stopSelf(); return START_NOT_STICKY;
        }
    }
    void activate(long seconds) { if(gatt!=null) gatt.activate(seconds); }
    static void glucoseAvailable(long milliseconds,int glucose,float rate) {
        Libre3EmulatorService service=instance;
        Libre3EmulatorGatt transport=service==null?null:service.gatt;
        if(transport!=null && !service.destroyed) {
            int change=Float.isNaN(rate)?Short.MIN_VALUE:Math.max(-32767,Math.min(32767,Math.round(rate*100)));
            transport.glucoseAvailable(new Libre3EmulatorProtocol.Reading(milliseconds/1000L,glucose,change));
        }
    }
    boolean ownedBy(String node) { return owner.equals(node); }
    void publish() { if(!owner.isEmpty()) Libre3EmulatorWear.status(this,owner); }
    @Override public void onDestroy() {
        Libre3EmulatorLog.i(log,"destroy failed="+failed+" status="+Libre3Emulator.status);
        destroyed=true;
        if(gatt!=null) { gatt.close(); gatt=null; }
        if(wake!=null && wake.isHeld()) wake.release();
        Libre3Emulator.running=null; Libre3Emulator.token=null; Libre3Emulator.address="";
        Libre3Emulator.addressObservedAt=0; Libre3Emulator.discoveryStatus="";
        if(!failed) Libre3Emulator.status="Stopped";
        Libre3Emulator.prefs(this).edit().remove("activeConfig").remove("owner").apply();
        publish(); instance=null; stopForeground(true); super.onDestroy();
    }
}
