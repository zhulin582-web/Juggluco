/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicInteger;

/** Emulator diagnostics reach logcat even in builds without Juggluco trace logging. */
final class Libre3EmulatorLog {
    private static final String TAG="Libre3Emu";
    private static final AtomicInteger sequence=new AtomicInteger();
    private Libre3EmulatorLog() { }
    static String scope(String component) { return component+"#"+sequence.incrementAndGet(); }
    static void i(String scope,String message) { write("I",scope,message); }
    static void w(String scope,String message) { write("W",scope,message); }
    static void error(String scope,String message,Throwable error) {
        // Keep the cause on the tagged line when a trace is filtered by TAG.
        write("E",scope,message+": "+error+"\n"+android.util.Log.getStackTraceString(error));
    }
    private static void write(String level,String scope,String message) {
        String line="["+scope+" t="+SystemClock.elapsedRealtime()+"ms] "+message;
        if(level.equals("E")) android.util.Log.e(TAG,line);
        else if(level.equals("W")) android.util.Log.w(TAG,line);
        else android.util.Log.i(TAG,line);
        if(Log.doLog && Applic.Nativesloaded) Log.i(TAG,level+" "+line);
    }
    // Never include PINs, certificates, authorization records or packet contents.
    static String profile(Libre3EmulatorConfig c) {
        return "sensor="+c.serial+" host="+(c.targetNode.isEmpty()?"local":c.targetNode)+
            " unused="+c.unused+" start="+c.startTime+" patchState="+c.patchState+
            " warmup="+c.warmupMinutes+" wear="+c.wearMinutes+
            " source="+c.glucoseMode+" interval="+c.intervalSeconds+
            " certificateBytes="+(c.certificate==null?0:c.certificate.length);
    }
}
