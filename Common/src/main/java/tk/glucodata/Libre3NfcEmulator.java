/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import static tk.glucodata.Log.doLog;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Loads scan data into a Proxmark3 Easy running the accompanying firmware. */
public final class Libre3NfcEmulator implements Closeable {
    /** Enable USB/RF diagnostics in addition to the normal NFC exchange log. */
    public static final boolean NFC_EMULATOR_DEBUG = false;
    private static final boolean debugLog = doLog && NFC_EMULATOR_DEBUG;

    public interface Listener {
        /** Called on the main thread after the firmware reports ready. */
        void onReady();
        /** Called on the main thread after close() or the Proxmark button. */
        void onStopped();
        /** Called on the main thread; the USB connection has been released. */
        void onError(Exception error);
        /** A0/A8 reply attempted, regardless of diagnostic logging settings. */
        default void onConnectionScan(boolean activation, long requestedStart) { }
    }

    private static final AtomicInteger NEXT_REQUEST = new AtomicInteger();
    private static final String TAG = "Libre3NfcEmulator";
    private static final byte[] EMPTY = new byte[0];
    private final Context context;
    private final UsbManager manager;
    private final UsbDevice device;
    private final Libre3NfcProfile profile;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "Libre3NfcUsb"));
    private final AtomicBoolean finished = new AtomicBoolean();
    private final int requestCode = NEXT_REQUEST.incrementAndGet();
    private final String permissionAction;
    private PendingIntent permissionIntent;
    private boolean registered;
    private boolean workerStarted;
    private volatile boolean stopRequested;
    private volatile boolean detached;
    private volatile boolean ready;
    private final long createdAt = SystemClock.elapsedRealtime();
    private volatile String stage = "created";
    private volatile String lastRf = "no RF diagnostics received";
    private long lastFirmwareAt;
    private long lastHeartbeatAt;
    private boolean rfDiagnostics;

    private Libre3NfcEmulator(Context context, UsbDevice device, Libre3NfcProfile profile, Listener listener) {
        this.context = context.getApplicationContext();
        this.manager = (UsbManager)this.context.getSystemService(Context.USB_SERVICE);
        this.device = device;
        this.profile = profile;
        this.listener = listener;
        this.permissionAction = this.context.getPackageName() + ".LIBRE3_NFC_USB_PERMISSION." + requestCode;
        if (debugLog) {
            log("logging build v4; device=" + deviceDescription(device) + " Android=" + Build.VERSION.SDK_INT);
            byte[] uid = profile.nfcUid();
            log("profile serial=" + profile.serial() + " UID(wire)=" + Libre3Pm3Protocol.hex(uid, 0, uid.length) +
                    " startSeconds=" + profile.startTime() + " warmupMinutes=" + profile.warmupMinutes() +
                    " wearMinutes=" + profile.wearMinutes() + " patchState=" + profile.patchState());
        }
    }

    private void log(String message) {
        Log.i(TAG, "[" + requestCode + " +" + (SystemClock.elapsedRealtime() - createdAt) + "ms] " + message);
    }

    private static String deviceDescription(UsbDevice device) {
        return device.getDeviceName() + " VID:PID=" + Integer.toHexString(device.getVendorId()) + ":" +
                Integer.toHexString(device.getProductId()) + " interfaces=" + device.getInterfaceCount();
    }

    private void stage(String value) { stage = value; if (debugLog) log("stage=" + value); }

    public static List<UsbDevice> devices(Context context) {
        UsbManager manager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
        List<UsbDevice> found = new ArrayList<>();
        if (manager != null) {
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (debugLog) Log.i(TAG, "USB discovery " + deviceDescription(device) + " matches=" + isProxmark(device));
                if (isProxmark(device)) found.add(device);
            }
        }
        return found;
    }

    /** Starts the only attached Proxmark. Pass a device explicitly if more than one is attached. */
    public static Libre3NfcEmulator start(Context context, Libre3NfcProfile profile, Listener listener) {
        List<UsbDevice> found = devices(context);
        if (found.size() != 1) throw new IllegalStateException("Expected one Proxmark3 USB device; found " + found.size());
        return start(context, found.get(0), profile, listener);
    }

    public static Libre3NfcEmulator start(Context context, UsbDevice device, Libre3NfcProfile profile, Listener listener) {
        if (!isProxmark(device) || profile == null || listener == null) throw new IllegalArgumentException("Invalid emulator arguments");
        Libre3NfcEmulator result = new Libre3NfcEmulator(context, device, profile, listener);
        result.main.post(result::requestOrOpen);
        return result;
    }

    public boolean isReady() { return ready; }

    /** Asynchronous: stops RF emulation, then releases USB. */
    @Override public void close() {
        if (debugLog) log("close() requested; ready=" + ready + " stage=" + stage);
        stopRequested = true;
        main.post(() -> {
            if (!workerStarted) finish(null);
        });
    }

    private static boolean isProxmark(UsbDevice device) {
        return device != null && device.getVendorId() == 0x9ac4 && device.getProductId() == 0x4b8f;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (permissionAction.equals(intent.getAction())) {
                if (finished.get() || workerStarted) return;
                // Recheck the grant with UsbManager; do not trust broadcast extras.
                if (debugLog) log("USB permission result: granted=" + manager.hasPermission(device));
                if (manager.hasPermission(device)) openWorker();
                else finish(new IOException("USB permission was not granted"));
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (debugLog) log("USB detach broadcast; current USB devices=" + manager.getDeviceList().keySet());
                if (!manager.getDeviceList().containsKey(device.getDeviceName())) {
                    if (debugLog) log("Selected Proxmark is absent; stage=" + stage + "; last " + lastRf);
                    detached = true;
                    stopRequested = true;
                    if (!workerStarted) finish(new IOException("Proxmark3 disconnected"));
                }
            }
        }
    };

    private void requestOrOpen() {
        if (finished.get()) return;
        if (stopRequested) { finish(null); return; }
        try {
            stage("checking USB permission");
            IntentFilter filter = new IntentFilter(permissionAction);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter);
            registered = true;
            if (manager.hasPermission(device)) { if (debugLog) log("USB permission already granted"); openWorker(); }
            else {
                if (debugLog) log("Requesting Android USB permission");
                Intent permission = new Intent(permissionAction).setPackage(context.getPackageName());
                int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
                permissionIntent = PendingIntent.getBroadcast(context, requestCode, permission, flags);
                manager.requestPermission(device, permissionIntent);
            }
        } catch (Exception error) { finish(error); }
    }

    private void openWorker() {
        if (workerStarted || finished.get()) return;
        if (stopRequested) { finish(null); return; }
        workerStarted = true;
        worker.execute(this::run);
    }

    private void run() {
        Exception failure = null;
        Transport usb = null;
        boolean simulationSent = false;
        boolean simulationStopped = false;
        try {
            stage("opening USB");
            usb = new Transport(manager, device);
            stage("firmware handshake");
            usb.send(Libre3Pm3Protocol.BREAK, EMPTY);
            usb.send(Libre3Pm3Protocol.INFO, EMPTY);
            Libre3Pm3Protocol.Reply identity = usb.await(Libre3Pm3Protocol.INFO, 5000);
            int capabilities = Libre3Pm3Protocol.capabilities(identity);
            rfDiagnostics = (capabilities & 1) != 0;
            if (profile.patchState() == 1 && !rfDiagnostics) {
                throw new IOException("Unused-sensor activation requires the NFC diagnostic firmware");
            }
            if (debugLog) log("L3N1 ABI=1 profileBytes=64 capabilities=0x" + Integer.toHexString(capabilities) +
                    " RF diagnostics=" + rfDiagnostics);
            if (doLog && !rfDiagnostics) log("Firmware has no NFC exchange diagnostics; install the supplied v3 firmware");
            if (!stopRequested) {
                stage("uploading profile and starting RF");
                simulationSent = true;
                usb.send(Libre3Pm3Protocol.SIMULATE, profile.usbPayload());
                Libre3Pm3Protocol.Reply start = usb.await(Libre3Pm3Protocol.SIMULATE, 8000);
                if (start.status != 0 || start.payload.length != 1 || start.payload[0] != 1) {
                    throw new IOException("Proxmark3 rejected the NFC profile or could not start: " + start.status);
                }
                ready = true;
                stage("emulating");
                if (debugLog) {
                    log("Firmware ready: profile accepted; waiting for the other phone's NFC reader. This is not proof of a successful scan");
                    lastHeartbeatAt = SystemClock.elapsedRealtime();
                }
                main.post(() -> { if (!stopRequested && !finished.get()) listener.onReady(); });
                while (!stopRequested) {
                    // Reads only. Sending a USB command would interrupt RF emulation.
                    Libre3Pm3Protocol.Reply reply = usb.read(250);
                    if (debugLog) heartbeat(usb);
                    if (reply != null && reply.ng && reply.command == Libre3Pm3Protocol.SIMULATE) {
                        if (reply.status != 0 || reply.payload.length != 1 || reply.payload[0] != 0) {
                            throw new IOException("Unexpected emulator completion: " + reply.status);
                        }
                        simulationStopped = true;
                        if (debugLog) log("Firmware acknowledged emulation stopped");
                        break;
                    }
                }
            }
            if (detached) throw new IOException("Proxmark3 disconnected");
        } catch (Exception error) {
            failure = error;
            if (doLog) Log.stack(TAG, "Session " + requestCode + " failed at " + stage +
                    (debugLog ? "; " + lastRf : ""), error);
        } finally {
            ready = false;
            if (usb != null) {
                if (simulationSent && !simulationStopped && !detached) {
                    try {
                        stage("stopping RF");
                        usb.send(Libre3Pm3Protocol.BREAK, EMPTY);
                        long until = SystemClock.elapsedRealtime() + 2500;
                        boolean acknowledged = false;
                        while (SystemClock.elapsedRealtime() < until) {
                            Libre3Pm3Protocol.Reply reply = usb.read(250);
                            if (reply != null && reply.ng && reply.command == Libre3Pm3Protocol.SIMULATE &&
                                    reply.status == 0 && reply.payload.length == 1 && reply.payload[0] == 0) {
                                acknowledged = true;
                                break;
                            }
                        }
                        if (!acknowledged && failure == null) {
                            failure = new IOException("Stop was not acknowledged; press the Proxmark button or unplug it");
                        }
                        if (debugLog) log("Stop acknowledgment=" + acknowledged);
                    } catch (Exception stopError) { if (failure == null) failure = stopError; }
                }
                usb.close();
            }
            finish(failure);
        }
    }

    private void heartbeat(Transport usb) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastHeartbeatAt < 5000) return;
        lastHeartbeatAt = now;
        log("waiting: " + usb.statistics() + "; last firmware reply " + (now - lastFirmwareAt) + "ms ago; " + lastRf);
        if (rfDiagnostics && now - lastFirmwareAt > 5000) {
            log("No recent firmware telemetry: receiver may be busy/stalled, or USB input is not arriving; USB read timeouts alone do not prove disconnection");
        }
    }

    private void received(Libre3Pm3Protocol.Reply reply) throws IOException {
        if (debugLog) lastFirmwareAt = SystemClock.elapsedRealtime();
        if (reply.ng && reply.command == Libre3Pm3Protocol.DIAGNOSTIC) {
            Libre3Pm3Protocol.validateDiagnostic(reply.payload);
            long[] scan = Libre3Pm3Protocol.connectionScan(reply.payload);
            if (scan != null) main.post(() -> {
                if (!finished.get()) listener.onConnectionScan(scan[0] != 0, scan[1]);
            });
            if (debugLog) {
                lastRf = Libre3Pm3Protocol.diagnostic(reply.payload);
                log(lastRf);
            }
            if (doLog) {
                String exchange = Libre3Pm3Protocol.nfcExchange(reply.payload, profile);
                if (exchange != null) log(exchange);
            }
        } else if (reply.ng && reply.command == Libre3Pm3Protocol.DEBUG_STRING && reply.payload.length >= 2) {
            if (debugLog) log("PM3 debug: " + new String(reply.payload, 2, reply.payload.length - 2, StandardCharsets.UTF_8));
        } else if (debugLog) {
            log("USB RX command=0x" + Integer.toHexString(reply.command) + " status=" + reply.status +
                    " NG=" + reply.ng + " payloadBytes=" + reply.payload.length +
                    ((reply.command == Libre3Pm3Protocol.INFO || reply.command == Libre3Pm3Protocol.SIMULATE) ?
                            " [" + Libre3Pm3Protocol.hex(reply.payload, 0, reply.payload.length) + "]" : ""));
        }
    }

    private void finish(Exception error) {
        if (!finished.compareAndSet(false, true)) return;
        ready = false;
        if (debugLog) log("finish: " + (error == null ? "stopped" : error.toString()) + "; last " + lastRf);
        worker.shutdown();
        main.post(() -> {
            if (permissionIntent != null) permissionIntent.cancel();
            if (registered) {
                context.unregisterReceiver(receiver);
                registered = false;
            }
            if (error == null) listener.onStopped();
            else listener.onError(error);
        });
    }

    private final class Transport implements Closeable {
        private final UsbDeviceConnection connection;
        private UsbInterface control;
        private UsbInterface data;
        private UsbEndpoint in;
        private UsbEndpoint out;
        private boolean controlClaimed;
        private boolean dataClaimed;
        private final byte[] input = new byte[4096];
        private final Libre3Pm3Protocol.Decoder decoder = new Libre3Pm3Protocol.Decoder();
        private long inputBytes;
        private long outputBytes;
        private long replies;
        private long emptyReads;

        Transport(UsbManager manager, UsbDevice device) throws IOException {
            connection = manager.openDevice(device);
            if (connection == null) throw new IOException("Cannot open Proxmark3 USB device");
            try {
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    UsbInterface iface = device.getInterface(i);
                    if (debugLog) log("USB interface id=" + iface.getId() + " class=" + iface.getInterfaceClass() +
                            " subclass=" + iface.getInterfaceSubclass() + " endpoints=" + iface.getEndpointCount());
                    if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) control = iface;
                    if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                        UsbEndpoint candidateIn = null, candidateOut = null;
                        for (int j = 0; j < iface.getEndpointCount(); j++) {
                            UsbEndpoint ep = iface.getEndpoint(j);
                            if (debugLog) log("USB endpoint=0x" + Integer.toHexString(ep.getAddress()) + " type=" + ep.getType() +
                                    " maxPacket=" + ep.getMaxPacketSize());
                            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                            if (ep.getDirection() == UsbConstants.USB_DIR_IN) candidateIn = ep;
                            else candidateOut = ep;
                        }
                        if (candidateIn != null && candidateOut != null) {
                            data = iface; in = candidateIn; out = candidateOut;
                        }
                    }
                }
                if (control == null || data == null) throw new IOException("Expected Proxmark3 CDC firmware, not legacy HID");
                controlClaimed = connection.claimInterface(control, true);
                dataClaimed = connection.claimInterface(data, true);
                if (debugLog) log("USB claimed control=" + controlClaimed + " data=" + dataClaimed);
                if (!controlClaimed || !dataClaimed) throw new IOException("Cannot claim Proxmark3 USB interfaces");
                // 115200, one stop bit, no parity, eight data bits. CDC ignores baud on PM3.
                byte[] coding = {0, (byte)0xc2, 1, 0, 0, 0, 8};
                int lineCoding = connection.controlTransfer(0x21, 0x20, 0, control.getId(), coding, coding.length, 1000);
                int lineState = connection.controlTransfer(0x21, 0x22, 3, control.getId(), null, 0, 1000);
                if (debugLog) log("USB CDC SET_LINE_CODING=" + lineCoding + " SET_CONTROL_LINE_STATE=" + lineState);
                if (lineCoding != coding.length || lineState < 0) {
                    throw new IOException("Cannot initialize Proxmark3 CDC connection");
                }
            } catch (IOException | RuntimeException error) {
                close();
                throw error;
            }
        }

        void send(int command, byte[] payload) throws IOException {
            if (debugLog) log("USB TX command=0x" + Integer.toHexString(command) + " payloadBytes=" + payload.length);
            byte[] frame = Libre3Pm3Protocol.command(command, payload);
            int pos = 0;
            while (pos < frame.length) {
                int n = connection.bulkTransfer(out, frame, pos, frame.length - pos, 2000);
                if (n <= 0) throw new IOException("Proxmark3 USB write failed: result=" + n + " offset=" + pos + "/" + frame.length);
                pos += n;
                if (debugLog) outputBytes += n;
            }
        }

        Libre3Pm3Protocol.Reply read(int timeoutMs) throws IOException {
            Libre3Pm3Protocol.Reply ready = decoder.next();
            if (ready == null) {
                int n = connection.bulkTransfer(in, input, input.length, timeoutMs);
                if (n > 0) { if (debugLog) inputBytes += n; decoder.append(input, n); }
                else if (debugLog) emptyReads++;
                ready = decoder.next();
            }
            if (ready != null) { if (debugLog) replies++; received(ready); }
            return ready;
        }

        Libre3Pm3Protocol.Reply await(int command, int timeoutMs) throws IOException {
            long until = SystemClock.elapsedRealtime() + timeoutMs;
            while (SystemClock.elapsedRealtime() < until) {
                if (detached) throw new IOException("Proxmark3 disconnected during " + stage);
                Libre3Pm3Protocol.Reply reply = read(250);
                if (reply != null && reply.ng && reply.command == command) return reply;
            }
            if (debugLog) log("Timeout awaiting USB command=0x" + Integer.toHexString(command) + " after " + timeoutMs + "ms; " + statistics());
            throw new IOException(command == Libre3Pm3Protocol.INFO ?
                    "No L3N1 reply: install the accompanying Proxmark3 firmware" : "Timed out waiting for Proxmark3");
        }

        String statistics() {
            return "USB inBytes=" + inputBytes + " outBytes=" + outputBytes + " frames=" + replies + " empty/errorReads=" + emptyReads;
        }

        @Override public void close() {
            if (debugLog) log("Releasing USB; detached=" + detached + "; " + statistics());
            try {
                if (controlClaimed && !detached) connection.controlTransfer(0x21, 0x22, 0, control.getId(), null, 0, 250);
                if (dataClaimed) {
                    boolean released = connection.releaseInterface(data);
                    if (debugLog) log("Release data interface=" + released);
                }
                if (controlClaimed) {
                    boolean released = connection.releaseInterface(control);
                    if (debugLog) log("Release control interface=" + released);
                }
            } catch (RuntimeException error) {
                if (doLog) Log.stack(TAG, "USB release failed", error);
            } finally {
                try { connection.close(); }
                catch (RuntimeException error) { if (doLog) Log.stack(TAG, "USB close failed", error); }
            }
        }
    }


    /** Kept for existing menu integrations. */
    public static void starttestemu() { Libre3Emulator.show(Applic.app); }
}
