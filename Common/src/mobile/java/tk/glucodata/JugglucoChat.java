// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.FragmentManager;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ComponentCallbacks;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.text.style.QuoteSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Keep;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialog;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.gms.wearable.Node;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android UI only. Authentication, model communication and CGM access are native. */
@Keep
public final class JugglucoChat {
    private static final long POLL_MS = 400L;
    private static final long WAKE_LIMIT_MS = 15L * 60L * 1000L;
    private static WeakReference<ChatWindow> current = new WeakReference<>(null);
    private static RunController running;

    private JugglucoChat() {}

    private static native String nativeInitWithFiles(String storageDir, String filesDir);
    private static native String nativeStartLogin();
    private static native String nativeLoadModels();
    private static native String nativeSendWithOptions(String question, String model, String reasoningEffort, boolean internet);
    private static native String nativeCancel();
    private static native String nativeNewChat();
    private static native String nativeClearAnalysis();
    private static native String nativeLogout();
    private static native String nativePoll();
    private static native String nativePollWork();
    private static native String nativeSetWearNodes(String snapshot);
    private static native String nativeSetPhoneActivity(String snapshot);
    private static native String nativeGetPlot(int message, int plot);
    private static native String nativeListFiles();
    private static native String nativeFileUrl(String id, String file);
    private static native String nativeTakeBrowserFile();
    private static native String nativeExportFile(String filesDir, String id, String file, int descriptor);
    private static native String nativeWriteChatExport(String format, int descriptor);
    private static native String nativeWritePlotExport(String svg, int descriptor);
    private static native String nativeCopyChatExport(int input, int output);

    /** The manifest grants access only to the export cache, never app files. */
    @Keep
    public static final class ExportProvider extends FileProvider {
        public ExportProvider() { super(); }
    }

    /** Own the picker independently of the chat dialog. Android restores the
     * fragment/arguments and routes its result after Activity/process recreation.
     * Retaining it preserves an in-progress copy across configuration changes.
     * Uses the framework Fragment already available on all supported phones;
     * no MainActivity result hook or extra Android dependency is needed. */
    @Keep
    @SuppressWarnings("deprecation")
    public static final class SaveFileFragment extends Fragment {
        static final String TAG = "JugglucoChat.SaveFile";
        static final int CREATE_DOCUMENT = 1;
        boolean launched, preparing, writing, finished, delivered, resultError;
        String resultText = "", exportPath = "";

        public SaveFileFragment() {}

        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            setRetainInstance(true);
            if (state != null) {
                launched = state.getBoolean("launched");
                finished = state.getBoolean("finished");
                resultError = state.getBoolean("resultError");
                resultText = state.getString("resultText", "");
                exportPath = state.getString("exportPath", "");
                if (state.getBoolean("preparing") && !finished) {
                    // Do not accidentally export a different chat after a
                    // process restart during snapshot preparation.
                    finished = resultError = true;
                    resultText = "Preparing the export was interrupted. Try Save or Share again.";
                }
                if (state.getBoolean("writing") && !finished) {
                    // A new process cannot resume an old provider descriptor.
                    finished = resultError = true;
                    resultText = "Saving was interrupted. Try Save as again. A partial copy may remain at the selected location.";
                }
            }
        }
        @Override public void onSaveInstanceState(Bundle state) {
            super.onSaveInstanceState(state);
            state.putBoolean("launched", launched);
            state.putBoolean("writing", writing);
            state.putBoolean("preparing", preparing);
            state.putString("exportPath", exportPath);
            state.putBoolean("finished", finished);
            state.putBoolean("resultError", resultError);
            state.putString("resultText", resultText);
        }
        @Override public void onStart() {
            super.onStart();
            if (getArguments().containsKey("format") && exportPath.isEmpty() && !finished) {
                if (!preparing) prepareExport();
            } else launchPicker();
        }
        void launchPicker() {
            if (!isResumed() || launched || preparing || finished) return;
            launched = true;
            try {
                String name = exportPath.isEmpty() ? getArguments().getString("file") : new File(exportPath).getName();
                if (getArguments().getBoolean("share")) {
                    File file = exportFile(getActivity(), exportPath);
                    Uri uri = FileProvider.getUriForFile(getActivity(),
                            getActivity().getPackageName() + ".chat.exports", file);
                    Intent intent = new Intent(Intent.ACTION_SEND).setType(documentType(name))
                            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, name)
                            .putExtra(Intent.EXTRA_SUBJECT, name)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    // ClipData also propagates read grants on older Android.
                    intent.setClipData(ClipData.newRawUri(name, uri));
                    // Text-only receivers often use EXTRA_TEXT. Always retain
                    // the full attachment; avoid oversized Binder transactions.
                    if (name.endsWith(".txt") && file.length() <= 65536) {
                        try (FileInputStream input = new FileInputStream(file)) {
                            ByteArrayOutputStream contents = new ByteArrayOutputStream();
                            byte[] buffer = new byte[4096]; int count;
                            while ((count = input.read(buffer)) != -1) contents.write(buffer, 0, count);
                            intent.putExtra(Intent.EXTRA_TEXT, new String(contents.toByteArray(), StandardCharsets.UTF_8));
                        }
                    }
                    startActivity(Intent.createChooser(intent, "Share " + (name.endsWith(".svg") ? "plot" : "chat")));
                    complete("", false);
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType(documentType(name))
                        .putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(intent, CREATE_DOCUMENT);
            } catch (ActivityNotFoundException failure) {
                complete(getArguments().getBoolean("share")
                        ? "No installed app can receive this file format. Try Save instead."
                        : "No Android document picker is available.", true);
            } catch (IOException failure) {
                complete("The prepared export is no longer available. Try Save or Share again.", true);
            } catch (RuntimeException failure) {
                complete(getArguments().getBoolean("share")
                        ? "Could not share the export. Check that the chat export provider and XML resource are installed."
                        : "Could not open Android's Save as screen.", true);
            }
        }
        @Override public void onResume() { super.onResume(); launchPicker(); deliverResult(); }
        void prepareExport() {
            preparing = true;
            final Context app = getActivity().getApplicationContext();
            final String format = getArguments().getString("format");
            final String svg = getArguments().getString("svg");
            Toast.makeText(app, "Preparing export…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                File file = null;
                String error = "";
                try {
                    if (!"html".equals(format) && !"txt".equals(format) && !"svg".equals(format))
                        throw new IOException("Unsupported format");
                    File directory = new File(app.getCacheDir(), "juggluco-chat-exports");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create export cache");
                    File[] old = directory.listFiles();
                    long cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L;
                    if (old != null) for (File entry : old)
                        if (entry.isFile() && entry.getName().startsWith("Juggluco-") && entry.lastModified() < cutoff) entry.delete();
                    String date = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                    file = File.createTempFile("Juggluco-" + ("svg".equals(format) ? "plot-" : "chat-") + date + "-", "." + format, directory);
                    try (ParcelFileDescriptor output = ParcelFileDescriptor.open(file,
                            ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE)) {
                        String result = "svg".equals(format) ? nativeWritePlotExport(svg, output.getFd())
                                : nativeWriteChatExport(format, output.getFd());
                        if (result != null) error = result;
                    }
                } catch (Exception | LinkageError failure) { error = "Could not prepare the export. Try again."; }
                final File prepared = file;
                final String failure = error;
                new Handler(Looper.getMainLooper()).post(() -> {
                    preparing = false;
                    if (!failure.isEmpty() || prepared == null) {
                        if (prepared != null) prepared.delete();
                        complete(failure.isEmpty() ? "Could not prepare the export." : failure, true);
                    } else {
                        exportPath = prepared.getAbsolutePath();
                        launchPicker();
                    }
                });
            }, "JugglucoChat-export").start();
        }
        // Arguments survive Android process recreation. Still require the
        // exact private export directory before opening a persisted path.
        static File exportFile(Context context, String path) throws IOException {
            File root = new File(context.getCacheDir(), "juggluco-chat-exports").getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            if (!root.equals(file.getParentFile()) || !file.getName().startsWith("Juggluco-") || !file.isFile())
                throw new IOException("Export snapshot is unavailable");
            return file;
        }
        @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode != CREATE_DOCUMENT || writing || finished) return;
            if (resultCode != Activity.RESULT_OK) { complete("", false); return; }
            final Uri uri = data == null ? null : data.getData();
            if (uri == null || !"content".equals(uri.getScheme()) || getActivity() == null) {
                complete("Android did not return a writable document.", true); return;
            }
            final Context app = getActivity().getApplicationContext();
            final String id = getArguments().getString("id"), file = getArguments().getString("file");
            final String snapshot = exportPath;
            writing = true;
            Toast.makeText(app, "Saving file…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                boolean failed = false;
                try (ParcelFileDescriptor document = app.getContentResolver().openFileDescriptor(uri, "wt")) {
                    if (document == null) throw new IOException("No document descriptor");
                    String error;
                    if (snapshot.isEmpty()) error = nativeExportFile(app.getFilesDir().getAbsolutePath(), id, file, document.getFd());
                    else try (ParcelFileDescriptor input = ParcelFileDescriptor.open(exportFile(app, snapshot), ParcelFileDescriptor.MODE_READ_ONLY)) {
                        error = nativeCopyChatExport(input.getFd(), document.getFd());
                    }
                    if (error != null) {
                        try { document.closeWithError("Could not copy generated file"); } catch (IOException ignored) {}
                        throw new IOException("Native export failed");
                    }
                    document.checkError();
                } catch (Exception | LinkageError failure) {
                    // Provider messages may contain private locations. Keep them
                    // out of chat/model data and diagnostic logs.
                    failed = true;
                }
                final boolean failedCopy = failed;
                new Handler(Looper.getMainLooper()).post(() -> complete(failedCopy
                        ? (snapshot.isEmpty() ? "Could not save the file. The original is still in Saved files. "
                            : "Could not save the export. Try Save chat or Save plot again. ")
                            + "An empty or partial copy may remain at the selected location."
                        : "File saved.", failedCopy));
            }, "JugglucoChat-save").start();
        }
        void complete(String message, boolean error) {
            writing = false; finished = true; resultText = message; resultError = error;
            deliverResult();
        }
        void deliverResult() {
            if (!finished || delivered || !isResumed() || getActivity() == null) return;
            delivered = true;
            if (!resultText.isEmpty()) {
                ChatWindow chat = current.get();
                if (resultError && chat != null && !chat.closed && chat.activity == getActivity()) {
                    chat.localError = resultText; chat.showError();
                } else Toast.makeText(getActivity(), resultText, Toast.LENGTH_LONG).show();
            }
            getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }

    private static String documentType(String name) {
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".tsv")) return "text/tab-separated-values";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".md")) return "text/markdown";
        return "text/plain";
    }

    public static void show(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> show(activity));
            return;
        }
        if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
        if (running == null) running = new RunController(activity.getApplication());
        running.hostClass = activity.getClass();
        running.shortcutWanted = false;
        running.removeShortcut();
        ChatWindow previous = current.get();
        if (previous != null && !previous.closed) {
            if (previous.activity == activity) {
                previous.refresh();
                return;
            }
            previous.dialog.dismiss();
        }
        ChatWindow window = new ChatWindow(activity);
        current = new WeakReference<>(window);
        window.open();
    }

    private interface NativeCall { String run(); }

    /** Application-owned observation and wake lease. Closing a window only
     * detaches its views; it never owns or cancels the native request. All
     * methods run on the main thread and Activity/View references are weak. */
    private static final class RunController implements Application.ActivityLifecycleCallbacks {
        final Application app;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable pollTask = this::poll;
        PowerManager.WakeLock wakeLock;
        long observedOperation = -1, completedOperation = -1;
        long wearObservedAt;
        boolean busy, initialized, polling, pollAgain, settingsOpen;
        boolean shortcutWanted, reopenAfterRotation, restoreKeyboard;
        String draft = "";
        int selectionStart, selectionEnd, scrollY;
        boolean followTail = true;
        Class<?> hostClass;
        WeakReference<AppCompatButton> shortcut = new WeakReference<>(null);
        WeakReference<View> shortcutContainer = new WeakReference<>(null);
        WeakReference<ViewGroup> shortcutParent = new WeakReference<>(null);
        WeakReference<Activity> shortcutHost = new WeakReference<>(null);
        ViewTreeObserver.OnGlobalLayoutListener shortcutLayout;
        ViewTreeObserver.OnWindowFocusChangeListener shortcutFocus;

        RunController(Application app) {
            this.app = app;
            app.registerActivityLifecycleCallbacks(this);
        }
        boolean visible() {
            ChatWindow chat = current.get();
            return chat != null && !chat.closed && chat.resumed && chat.initialized;
        }
        void refresh() {
            if (!initialized) return;
            if (polling) { pollAgain = true; return; }
            handler.removeCallbacks(pollTask);
            poll();
        }
        void poll() {
            if (!initialized || polling) return;
            polling = true;
            try {
                long now = android.os.SystemClock.elapsedRealtime();
                if (wearObservedAt == 0 || now - wearObservedAt >= 5000) {
                    wearObservedAt = now;
                    publishWearNodes();
                    publishPhoneActivity();
                }
                boolean full = visible();
                String raw = full ? nativePoll() : nativePollWork();
                if (raw == null) throw new JSONException("No chat status");
                JSONObject state = new JSONObject(raw);
                busy = state.optBoolean("busy", false);
                long operation = state.optLong("operation_id", 0);
                if (operation != observedOperation) {
                    releaseWake();
                    observedOperation = operation;
                    long remaining = WAKE_LIMIT_MS - state.optLong("elapsed_ms", 0);
                    if (busy && remaining > 0) {
                        try {
                            PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                            if (power != null) {
                                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Juggluco:NativeChat");
                                wakeLock.setReferenceCounted(false);
                                wakeLock.acquire(Math.min(remaining, WAKE_LIMIT_MS));
                            }
                        } catch (RuntimeException unavailable) { releaseWake(); }
                    }
                }
                if (!busy) {
                    releaseWake();
                    if (operation != completedOperation) {
                        completedOperation = operation;
                        String unanswered = state.optString("pending_question", "");
                        if (draft.isEmpty() && !unanswered.isEmpty()) {
                            draft = unanswered;
                            selectionStart = selectionEnd = draft.length();
                        }
                    }
                }
                AppCompatButton chip = shortcut.get();
                if (chip != null) {
                    chip.setText(busy ? "Chat…" : "Chat");
                    chip.setContentDescription(busy ? "Return to chat; request in progress" : "Return to chat");
                }
                ChatWindow chat = current.get();
                if (full && chat != null && !chat.closed) chat.apply(state);
            } catch (JSONException | RuntimeException | LinkageError failure) {
                ChatWindow chat = current.get();
                if (chat != null && !chat.closed) {
                    chat.localError = "Could not update chat status. Check that Java and native code were both updated.";
                    chat.showError();
                }
            } finally {
                polling = false;
                handler.removeCallbacks(pollTask);
                if (pollAgain) { pollAgain = false; handler.post(pollTask); }
                else if (busy || visible()) handler.postDelayed(pollTask, POLL_MS);
            }
        }
        static Object seconds(long millis) { return millis > 0 ? millis / 1000L : JSONObject.NULL; }
        static Object textOrNull(String value) {
            return value == null || value.isEmpty() ? JSONObject.NULL : value.substring(0, Math.min(512, value.length()));
        }
        JSONObject sensorActivity() throws JSONException {
            JSONObject out = new JSONObject().put("status", "unavailable");
            try {
                BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                out.put("bluetooth_enabled", manager == null || manager.getAdapter() == null
                        ? JSONObject.NULL : manager.getAdapter().isEnabled());
            } catch (RuntimeException missingPermission) { out.put("bluetooth_enabled", JSONObject.NULL); }
            SensorBluetooth source = SensorBluetooth.blueone;
            if (source == null) return out;
            out.put("scan_started_at", seconds(source.scantime)).put("scan_timeout_at", seconds(source.scantimeouttime))
                    .put("scan_stopped_at", seconds(source.stopscantime));
            ArrayList<SuperGattCallback> callbacks = SensorBluetooth.mygatts();
            // Copy references before iterating; do not call JNI with a sensor
            // pointer that a Bluetooth callback could release concurrently.
            SuperGattCallback[] copy = callbacks == null ? new SuperGattCallback[0]
                    : callbacks.toArray(new SuperGattCallback[0]);
            JSONArray rows = new JSONArray();
            for (SuperGattCallback gatt : copy) {
                if (rows.length() == 64) break;
                if (gatt == null) continue;
                rows.put(new JSONObject().put("sensor_id", textOrNull(gatt.SerialNumber))
                        .put("protocol_generation", gatt.sensorgen).put("stopped", gatt.stop)
                        .put("address_known", gatt.mActiveDeviceAddress != null && !gatt.mActiveDeviceAddress.isEmpty())
                        .put("started_at", seconds(gatt.starttime)).put("connection_attempt_at", seconds(gatt.connectTime))
                        .put("found_at", seconds(gatt.foundtime)).put("connected_at", seconds(gatt.constatchange[0]))
                        .put("disconnected_at", seconds(gatt.constatchange[1]))
                        .put("connection_status", textOrNull(gatt.constatstatusstr))
                        .put("handshake_status", textOrNull(gatt.handshake))
                        .put("handshake_success_at", seconds(gatt.wrotepass[0]))
                        .put("handshake_failure_at", seconds(gatt.wrotepass[1]))
                        .put("glucose_success_at", seconds(gatt.charcha[0])).put("glucose_failure_at", seconds(gatt.charcha[1]))
                        .put("rssi_dbm", gatt.readrssi < 0 ? gatt.readrssi : JSONObject.NULL));
            }
            return out.put("status", "ok").put("total", copy.length).put("truncated", copy.length > rows.length())
                    .put("callbacks", rows);
        }
        JSONObject garminActivity() throws JSONException {
            JSONObject out = new JSONObject().put("status", "unavailable");
            if (Applic.app == null || Applic.app.numdata == null) return out;
            tk.glucodata.nums.AllData data = Applic.app.numdata;
            List<tk.glucodata.nums.AllData.GarminDeviceInfo> devices = data.getGarminDeviceInfos();
            JSONArray rows = new JSONArray();
            for (tk.glucodata.nums.AllData.GarminDeviceInfo watch : devices) {
                if (rows.length() == 64) break;
                rows.put(new JSONObject().put("id", Long.toString(watch.id)).put("name", textOrNull(watch.name))
                        .put("connected", watch.connected).put("direct_ble", watch.direct).put("active", watch.active)
                        .put("send_glucose", watch.glucose).put("numbers_device", watch.numbers)
                        .put("libre3_direct", watch.libre3Direct).put("libre3_installed", watch.libre3Installed)
                        .put("watch_stopped", watch.watchStopped).put("timestamped_glucose_ack", watch.timestampedGlucoseAck)
                        .put("app_version", watch.appVersion).put("communication_status", textOrNull(watch.communicationStatus))
                        .put("last_sent_at", seconds(watch.lastSend)).put("last_received_at", seconds(watch.lastReceived))
                        .put("last_status_at", seconds(watch.lastStatusTime))
                        .put("last_transport_status", watch.lastStatus == null ? JSONObject.NULL : watch.lastStatus.name())
                        .put("last_acknowledged_at", seconds(watch.lastAcknowledged))
                        .put("last_glucose_acknowledged_at", seconds(watch.lastGlucoseAcknowledged))
                        .put("acknowledged_glucose_sample_at", watch.acknowledgedGlucoseTime > 0
                                ? watch.acknowledgedGlucoseTime : JSONObject.NULL)
                        .put("last_error", textOrNull(watch.lastError)));
            }
            return out.put("status", "ok").put("has_active_watch", data.usewatch)
                    .put("transport_mode", data.getGarminTransportMode(app))
                    .put("total", devices.size()).put("truncated", devices.size() > rows.length()).put("watches", rows);
        }
        void publishPhoneActivity() {
            try {
                JSONObject sensor, garmin;
                try { sensor = sensorActivity(); }
                catch (RuntimeException | LinkageError unavailable) {
                    sensor = new JSONObject().put("status", "unavailable");
                }
                try { garmin = garminActivity(); }
                catch (RuntimeException | LinkageError unavailable) {
                    garmin = new JSONObject().put("status", "unavailable");
                }
                String error = nativeSetPhoneActivity(new JSONObject().put("sensors", sensor).put("garmin", garmin).toString());
                if (error != null) Log.i("JugglucoChat", "Phone activity snapshot rejected");
            } catch (JSONException | RuntimeException | LinkageError unavailable) {
                Log.i("JugglucoChat", "Phone activity snapshot unavailable");
            }
        }
        void publishWearNodes() {
            try {
                JSONArray nodes = new JSONArray();
                MessageSender sender = MessageSender.getMessageSender();
                java.util.Set<Node> cached = sender == null ? null : sender.getNodes();
                if (cached != null) for (Node node : cached) {
                    if (nodes.length() == 64) break;
                    nodes.put(new JSONObject().put("id", node.getId()).put("name", node.getDisplayName())
                            .put("nearby", node.isNearby()));
                }
                nativeSetWearNodes(new JSONObject().put("available", cached != null).put("nodes", nodes).toString());
            } catch (JSONException | RuntimeException | LinkageError unavailable) {
                // Optional Android cache only. Never initiate discovery or stop
                // a question if Google services / this cache are unavailable.
            }
        }
        void releaseWake() {
            PowerManager.WakeLock held = wakeLock; wakeLock = null;
            if (held != null) try { if (held.isHeld()) held.release(); } catch (RuntimeException ignored) {}
        }
        void removeShortcut() {
            ViewGroup parent = shortcutParent.get();
            if (parent != null && parent.getViewTreeObserver().isAlive()) {
                if (shortcutLayout != null) parent.getViewTreeObserver().removeOnGlobalLayoutListener(shortcutLayout);
                if (shortcutFocus != null) parent.getViewTreeObserver().removeOnWindowFocusChangeListener(shortcutFocus);
            }
            shortcutLayout = null; shortcutFocus = null;
            View container = shortcutContainer.get();
            if (container != null && container.getParent() instanceof ViewGroup)
                ((ViewGroup) container.getParent()).removeView(container);
            shortcut.clear(); shortcutContainer.clear(); shortcutParent.clear(); shortcutHost.clear();
        }
        boolean shortcutEnabled() {
            return app.getSharedPreferences("juggluco_chat", Context.MODE_PRIVATE).getBoolean("curve_shortcut", true);
        }
        void hideShortcut() {
            app.getSharedPreferences("juggluco_chat", Context.MODE_PRIVATE).edit().putBoolean("curve_shortcut", false).apply();
            shortcutWanted = false;
            removeShortcut();
        }
        void updateShortcutVisibility(ViewGroup parent, View curve) {
            View container = shortcutContainer.get();
            if (container == null || parent == null) return;
            boolean visible = shortcutWanted && shortcutEnabled() && curve != null
                    && curve.getParent() == parent && curve.isShown() && parent.hasWindowFocus();
            for (int i = 0; visible && i < parent.getChildCount(); ++i) {
                View child = parent.getChildAt(i);
                if (child != curve && child != container && child.getVisibility() == View.VISIBLE) visible = false;
            }
            int visibility = visible ? View.VISIBLE : View.GONE;
            if (container.getVisibility() != visibility) container.setVisibility(visibility);
        }
        void showShortcut(Activity activity) {
            if (!shortcutWanted || !shortcutEnabled() || activity.isFinishing() || activity.isDestroyed()) return;
            removeShortcut();
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout) || !(activity instanceof MainActivity)) return;
            FrameLayout parent = (FrameLayout) content;
            View curve = ((MainActivity) activity).curve;
            if (curve == null || curve.getParent() != parent) return;
            Context theme = new AlertDialog.Builder(activity).getContext();
            LinearLayout container = new LinearLayout(theme);
            container.setGravity(Gravity.CENTER_VERTICAL);
            AppCompatButton chip = new AppCompatButton(new AlertDialog.Builder(activity).getContext());
            chip.setAllCaps(false);
            chip.setText(busy ? "Chat…" : "Chat");
            chip.setContentDescription("Return to chat. Hold to hide this shortcut.");
            int gap = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            AppCompatButton hide = new AppCompatButton(theme);
            hide.setText("×"); hide.setAllCaps(false);
            hide.setContentDescription("Hide Chat shortcut. Enable it again in chat settings.");
            hide.setMinWidth(0); hide.setMinimumWidth(0); hide.setPadding(0, 0, 0, 0);
            container.addView(chip);
            container.addView(hide, new LinearLayout.LayoutParams(gap * 6, ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            params.setMarginEnd(gap); params.bottomMargin = gap;
            // Insert beside the curve, below any subsequently opened Android
            // panels. Also hide on visible sibling panels or another window.
            container.setVisibility(View.GONE);
            parent.addView(container, parent.indexOfChild(curve) + 1, params);
            ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                FrameLayout.LayoutParams position = (FrameLayout.LayoutParams) view.getLayoutParams();
                position.setMarginEnd(gap + Math.max(bars.left, bars.right));
                position.bottomMargin = gap + bars.bottom;
                view.setLayoutParams(position);
                return insets;
            });
            final WeakReference<Activity> owner = new WeakReference<>(activity);
            chip.setOnClickListener(v -> { Activity target = owner.get(); if (target != null) show(target); });
            chip.setOnLongClickListener(v -> { hideShortcut(); return true; });
            hide.setOnClickListener(v -> hideShortcut());
            shortcut = new WeakReference<>(chip); shortcutHost = owner;
            shortcutContainer = new WeakReference<>(container); shortcutParent = new WeakReference<>(parent);
            final WeakReference<View> curveRef = new WeakReference<>(curve);
            final WeakReference<ViewGroup> parentRef = new WeakReference<>(parent);
            shortcutLayout = () -> updateShortcutVisibility(parentRef.get(), curveRef.get());
            shortcutFocus = focused -> updateShortcutVisibility(parentRef.get(), curveRef.get());
            parent.getViewTreeObserver().addOnGlobalLayoutListener(shortcutLayout);
            parent.getViewTreeObserver().addOnWindowFocusChangeListener(shortcutFocus);
            updateShortcutVisibility(parent, curve);
            ViewCompat.requestApplyInsets(container);
        }
        @Override public void onActivityResumed(Activity activity) {
            if (activity.getClass() != hostClass) return;
            if (reopenAfterRotation) {
                reopenAfterRotation = false;
                handler.post(() -> show(activity));
            } else if (shortcutWanted && current.get() == null) showShortcut(activity);
            refresh();
        }
        @Override public void onActivityDestroyed(Activity activity) {
            if (shortcutHost.get() == activity) removeShortcut();
        }
        @Override public void onActivityCreated(Activity a, Bundle state) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
    }

    static String answerHeading(String modelName, String modelId) {
        if (modelName != null && !modelName.trim().isEmpty()) return modelName.trim();
        if (modelId != null && !modelId.trim().isEmpty()) return modelId.trim();
        // Earlier releases did not record which model answered a message.
        return "ChatGPT";
    }

    /** Small, non-HTML formatter. Offsets map original UTF-16 positions to the
     * displayed text so source citations survive removed Markdown delimiters.
     * Kept independent of Android text classes for host-side regression tests. */
    static final class Markup {
        static final int BOLD = 1, ITALIC = 2, BOTH = 3, CODE = 4, HEADING = 5, QUOTE = 6, LINK = 7;
        static final class Mark {
            final int kind; int start, end; final String url;
            Mark(int kind, int start, int end, String url) { this.kind = kind; this.start = start; this.end = end; this.url = url; }
        }
        static final class Result {
            final String text; final int[] positions; final List<Mark> marks;
            Result(String text, int[] positions, List<Mark> marks) { this.text = text; this.positions = positions; this.marks = marks; }
        }
        static void range(boolean[] flags, int start, int end) {
            for (int i = start; i < end; ++i) flags[i] = true;
        }
        static Result parse(String source) {
            final int size = source.length();
            boolean[] remove = new boolean[size], code = new boolean[size], escaped = new boolean[size];
            char[] chars = source.toCharArray();
            List<Mark> marks = new ArrayList<>();
            Matcher fences = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n").matcher(source);
            while (fences.find()) {
                if (code[fences.start()]) continue;
                String token = fences.group(1);
                Matcher close = Pattern.compile("(?m)^ {0,3}" + Pattern.quote(token) + "[ \\t]*(?:\\n|$)").matcher(source);
                int bodyEnd = size, blockEnd = size;
                if (close.find(fences.end())) { bodyEnd = close.start(); blockEnd = close.end(); }
                range(code, fences.start(), blockEnd);
                range(remove, fences.start(), fences.end());
                range(remove, bodyEnd, blockEnd);
                marks.add(new Mark(CODE, fences.end(), bodyEnd, null));
            }
            for (int i = 0; i + 1 < size; ++i) {
                if (!code[i] && chars[i] == '\\' && "\\`*_{}[]()#+-.!>".indexOf(chars[i + 1]) >= 0) {
                    remove[i] = true; escaped[++i] = true;
                }
            }
            Matcher inline = Pattern.compile("(`+)([^`\\n]+)\\1").matcher(source);
            while (inline.find()) {
                if (code[inline.start()] || escaped[inline.start()]) continue;
                range(code, inline.start(), inline.end());
                range(remove, inline.start(), inline.start(2));
                range(remove, inline.end(2), inline.end());
                marks.add(new Mark(CODE, inline.start(2), inline.end(2), null));
            }
            Matcher lines = Pattern.compile("(?m)^ {0,3}(#{1,6}[ \\t]+|>[ \\t]?|[-+*][ \\t]+)([^\\n]+)").matcher(source);
            while (lines.find()) {
                if (code[lines.start()] || escaped[lines.start(1)]) continue;
                char marker = source.charAt(lines.start(1));
                if (marker == '#' || marker == '>') {
                    range(remove, lines.start(), lines.start(2));
                    marks.add(new Mark(marker == '#' ? HEADING : QUOTE, lines.start(2), lines.end(2), null));
                } else chars[lines.start(1)] = '•';
            }
            Matcher links = Pattern.compile("\\[([^]\\n]+)\\]\\((https?://[^\\s)]+)\\)").matcher(source);
            while (links.find()) {
                if (code[links.start()] || escaped[links.start()]) continue;
                range(remove, links.start(), links.start(1));
                range(remove, links.end(1), links.end());
                if (links.start() > 0 && chars[links.start() - 1] == '!' && !escaped[links.start() - 1]) remove[links.start() - 1] = true;
                marks.add(new Mark(LINK, links.start(1), links.end(1), links.group(2)));
            }
            String[] patterns = {
                "(?<![\\\\*])\\*\\*\\*(?=\\S)([^*\\n]+?)\\*\\*\\*(?!\\*)",
                "(?<![\\\\*])\\*\\*(?=\\S)([^\\n]+?)\\*\\*(?!\\*)",
                "(?<![\\\\\\w_])__(?=\\S)([^_\\n]+?)__(?![\\w_])",
                "(?<![\\\\*])\\*(?=\\S)([^*\\n]+?)\\*(?!\\*)",
                "(?<![\\\\\\w_])_(?=\\S)([^_\\n]+?)_(?![\\w_])"
            };
            int[] kinds = {BOTH, BOLD, BOLD, ITALIC, ITALIC};
            for (int p = 0; p < patterns.length; ++p) {
                Matcher match = Pattern.compile(patterns[p]).matcher(source);
                while (match.find()) {
                    if (code[match.start()] || code[match.end() - 1] || remove[match.start()] || escaped[match.start()] ||
                            escaped[match.end() - 1] || Character.isWhitespace(source.charAt(match.end(1) - 1))) continue;
                    range(remove, match.start(), match.start(1)); range(remove, match.end(1), match.end());
                    marks.add(new Mark(kinds[p], match.start(1), match.end(1), null));
                }
            }
            int[] positions = new int[size + 1]; StringBuilder out = new StringBuilder(size);
            for (int i = 0; i < size; ++i) { positions[i] = out.length(); if (!remove[i]) out.append(chars[i]); }
            positions[size] = out.length();
            for (Mark mark : marks) { mark.start = positions[mark.start]; mark.end = positions[mark.end]; }
            return new Result(out.toString(), positions, marks);
        }
    }

    private static final class Model {
        final String id;
        final String name;
        final List<String> efforts = new ArrayList<>();
        final String recommendedEffort;
        Model(String id, String name, JSONObject metadata) {
            this.id = id;
            this.name = name;
            // Normalized by the C++ catalog parser; includes only implemented
            // wire options that this account's model explicitly advertises.
            JSONArray options = metadata.optJSONArray("reasoning_efforts");
            if (options != null) for (int i = 0; i < options.length(); ++i) {
                String option = options.optString(i, "");
                if (!option.isEmpty() && !efforts.contains(option)) efforts.add(option);
            }
            String recommended = metadata.optString("recommended_reasoning_effort", "");
            recommendedEffort = efforts.contains(recommended) ? recommended : "";
        }
        String label() { return name; }
    }

    private static final class ChatWindow implements Application.ActivityLifecycleCallbacks, ComponentCallbacks {
        final Activity activity;
        final Application application;
        final Context ui;
        final SharedPreferences prefs;
        final Handler handler = new Handler(Looper.getMainLooper());
        final AppCompatDialog dialog;
        final LinearLayout root;
        final FrameLayout chatSurface;
        final AppCompatButton keyboardDone;
        final LinearLayout chatPanel;
        final LinearLayout modelPanel;
        final LinearLayout progressRow;
        final NestedScrollView settingsScroll;
        final AppCompatButton settings;
        final AppCompatButton closeButton;
        final int backgroundColor;
        final AppCompatTextView status;
        final AppCompatTextView elapsed;
        final ProgressBar thinking;
        final AppCompatTextView error;
        final AppCompatTextView transcript;
        final AppCompatTextView code;
        final AppCompatTextView loginLink;
        final AppCompatEditText question;
        final AppCompatButton signIn;
        final AppCompatButton signOut;
        final AppCompatButton newChat;
        final AppCompatButton send;
        final AppCompatButton chooseModel;
        final AppCompatButton chooseReasoning;
        final AppCompatCheckBox internet;
        final AppCompatButton savedFiles;
        final AppCompatButton copyCode;
        final AppCompatButton openBrowser;
        final LinearLayout loginPanel;
        final NestedScrollView transcriptScroll;
        final List<Model> models = new ArrayList<>();
        AlertDialog childDialog;
        AppCompatDialog chartDialog;
        AppCompatTextView activityDetails;
        String activityText = "";
        boolean closed;
        boolean resumed = true;
        boolean initialized;
        boolean busy;
        boolean loggedIn;
        boolean settingsOpen;
        boolean haveAccountSnapshot;
        boolean modelsRequested;
        String localError = "";
        String serverError = "";
        String loginUrl = "";
        String userCode = "";
        String messageSnapshot = "";
        String modelSnapshot = "";
        String selectedModelId;
        int messageCount;
        boolean hasPendingQuestion;
        boolean forceTranscriptBottom;
        boolean restoreScroll = true;
        final Rect chatWindowBounds = new Rect();
        final Rect chatDecorBounds = new Rect();
        final Rect chatContentBounds = new Rect();
        final Rect chatAvailableBounds = new Rect();
        final Rect chatVisibleBounds = new Rect();
        final Rect chatCloseBounds = new Rect();
        final int[] chatScreenPosition = new int[2];
        String lastGeometryReport = "";
        final ViewTreeObserver.OnGlobalLayoutListener chatGeometryListener = this::applyChatGeometry;

        ChatWindow(Activity activity) {
            this.activity = activity;
            application = activity.getApplication();
            prefs = activity.getSharedPreferences("juggluco_chat", Context.MODE_PRIVATE);
            selectedModelId = prefs.getString("model", "");
            settingsOpen = running.settingsOpen;
            ui = new AlertDialog.Builder(activity).getContext();
            // Keep the Activity's non-floating window theme. A dialog-theme
            // window can still be fitted around system bars after MATCH_PARENT,
            // leaving a second navigation-bar-sized space above the keyboard.
            // Widgets retain the existing themed alert-dialog context in ui.
            dialog = new AppCompatDialog(activity, androidx.appcompat.R.style.Theme_AppCompat_Empty);
            dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
            TypedArray colors = ui.obtainStyledAttributes(new int[] {android.R.attr.colorBackground});
            backgroundColor = colors.getColor(0, 0);
            colors.recycle();

            root = column();
            root.setBackgroundColor(backgroundColor);
            root.setPadding(dp(8), 0, dp(8), 0);
            root.setFocusableInTouchMode(true);
            settings = button("More");
            closeButton = button("Close");
            compactFooterButton(settings);
            compactFooterButton(closeButton);

            // No title or toolbar above the editor. It grows with the draft,
            // consuming the transcript's space before it starts scrolling.
            question = new AppCompatEditText(ui);
            question.setHint("Ask about your Juggluco data");
            question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            question.setImeOptions(EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
            question.setMinLines(1);
            question.setVerticalScrollBarEnabled(true);
            question.setGravity(Gravity.TOP | Gravity.START);
            question.setText(running.draft);
            restoreSelection();
            send = button("Send");
            final int editorStart = question.getPaddingStart(), editorTop = question.getPaddingTop();
            final int editorEnd = question.getPaddingEnd(), editorBottom = question.getPaddingBottom();
            FrameLayout composer = new FrameLayout(ui) {
                @Override protected void onMeasure(int width, int height) {
                    measureChild(send, width, height);
                    int end = Math.max(editorEnd, send.getMeasuredWidth() + dp(8));
                    if (question.getPaddingEnd() != end)
                        question.setPaddingRelative(editorStart, editorTop, end, editorBottom);
                    super.onMeasure(width, height);
                }
            };
            composer.addView(question, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            composer.addView(send, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            chatPanel = new LinearLayout(ui) {
                @Override protected void onMeasure(int width, int height) {
                    if (View.MeasureSpec.getMode(height) != View.MeasureSpec.UNSPECIFIED) {
                        int available = Math.max(1, View.MeasureSpec.getSize(height) - getPaddingTop() - getPaddingBottom());
                        if (question.getMaxHeight() != available) question.setMaxHeight(available);
                    }
                    super.onMeasure(width, height);
                }
            };
            chatPanel.setOrientation(LinearLayout.VERTICAL);
            chatPanel.addView(composer, fullWidth());
            transcript = text("");
            transcript.setTextIsSelectable(true);
            transcript.setMovementMethod(LinkMovementMethod.getInstance());
            transcript.setPadding(dp(2), dp(4), dp(2), 0);
            transcriptScroll = new NestedScrollView(ui);
            transcriptScroll.setFillViewport(true);
            transcriptScroll.addView(transcript, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            chatPanel.addView(transcriptScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            root.addView(chatPanel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            // Account/model information occupies its own page. It is not part
            // of the logged-in chat's layout or scroll position.
            LinearLayout form = column();
            AppCompatTextView notice = text("Questions and requested Juggluco data are sent to OpenAI. "
                    + "This chat uses your Codex account and its usage limits; it is separate from the ChatGPT app.");
            notice.setTextSize(14);
            form.addView(notice, fullWidth());

            signIn = button("Sign in");
            signOut = button("Sign out");
            newChat = button("New chat");
            form.addView(row(signIn, signOut), fullWidth());
            form.addView(newChat, fullWidth());
            savedFiles = button("Saved files");
            form.addView(savedFiles, fullWidth());

            modelPanel = column();
            modelPanel.addView(text("Model"), fullWidth());
            chooseModel = button("Select a model");
            modelPanel.addView(chooseModel, fullWidth());
            chooseReasoning = button("Reasoning: Server default");
            modelPanel.addView(chooseReasoning, fullWidth());
            AppCompatTextView reasoningHint = text("High is selected initially when supported. "
                    + "More reasoning can take longer and use more of your account limits.");
            reasoningHint.setTextSize(14);
            modelPanel.addView(reasoningHint, fullWidth());
            internet = new AppCompatCheckBox(ui);
            internet.setText("Internet search");
            internet.setChecked(prefs.getBoolean("internet_search", true));
            internet.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("internet_search", checked).apply());
            modelPanel.addView(internet, fullWidth());
            AppCompatCheckBox curveShortcut = new AppCompatCheckBox(ui);
            curveShortcut.setText("Show Chat shortcut on the main curve");
            curveShortcut.setChecked(running.shortcutEnabled());
            curveShortcut.setOnCheckedChangeListener((button, checked) -> {
                prefs.edit().putBoolean("curve_shortcut", checked).apply();
                if (!checked) { running.shortcutWanted = false; running.removeShortcut(); }
            });
            modelPanel.addView(curveShortcut, fullWidth());
            form.addView(modelPanel, fullWidth());

            loginPanel = column();
            loginPanel.addView(text("Enter this one-time code on OpenAI’s sign-in page:"), fullWidth());
            code = text("");
            code.setTextSize(22);
            code.setTextIsSelectable(true);
            loginPanel.addView(code, fullWidth());
            loginLink = text("");
            loginLink.setTextIsSelectable(true);
            loginPanel.addView(loginLink, fullWidth());
            copyCode = button("Copy code");
            openBrowser = button("Open sign-in page");
            loginPanel.addView(row(copyCode, openBrowser), fullWidth());
            loginPanel.setVisibility(View.GONE);
            form.addView(loginPanel, fullWidth());

            settingsScroll = new NestedScrollView(ui);
            settingsScroll.setFillViewport(true);
            settingsScroll.addView(form, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(settingsScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            status = text("Opening chat…");
            status.setMaxLines(1);
            status.setEllipsize(TextUtils.TruncateAt.END);
            thinking = new ProgressBar(ui);
            thinking.setIndeterminate(true);
            elapsed = text("");
            elapsed.setPadding(dp(8), 0, 0, 0);
            LinearLayout progressButtons = new LinearLayout(ui);
            progressButtons.setGravity(Gravity.CENTER_VERTICAL);
            progressButtons.addView(thinking, new LinearLayout.LayoutParams(dp(24), dp(24)));
            progressButtons.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            progressButtons.addView(elapsed);
            progressRow = column();
            progressRow.addView(progressButtons, fullWidth());
            progressRow.setOnClickListener(v -> showActivity());
            progressRow.setContentDescription("Thinking progress. Tap for activity details.");
            root.addView(progressRow, fullWidth());
            error = text("");
            error.setTypeface(error.getTypeface(), Typeface.BOLD);
            error.setTextIsSelectable(true);
            error.setMaxLines(3);
            error.setVerticalScrollBarEnabled(true);
            error.setVisibility(View.GONE);
            root.addView(error, fullWidth());
            root.addView(row(settings, closeButton), fullWidth());

            // The chat retains its full size behind the keyboard. Only Done
            // follows the IME edge; it does not consume transcript height.
            chatSurface = new FrameLayout(ui);
            chatSurface.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            keyboardDone = button("Done");
            keyboardDone.setContentDescription("Done: hide keyboard");
            keyboardDone.setMinHeight(dp(40));
            keyboardDone.setMinimumHeight(0);
            keyboardDone.setPadding(dp(16), dp(4), dp(16), dp(4));
            keyboardDone.setIncludeFontPadding(false);
            keyboardDone.setFocusable(false);
            GradientDrawable doneBackground = new GradientDrawable();
            doneBackground.setColor(backgroundColor);
            doneBackground.setCornerRadius(dp(20));
            doneBackground.setStroke(dp(1), keyboardDone.getCurrentTextColor());
            keyboardDone.setBackground(doneBackground);
            keyboardDone.setElevation(dp(8));
            keyboardDone.setVisibility(View.GONE);
            keyboardDone.setOnClickListener(v -> { hideKeyboard(); root.requestFocus(); });
            chatSurface.addView(keyboardDone, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT));
            dialog.setContentView(chatSurface, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(ignored -> close());
            dialog.getOnBackPressedDispatcher().addCallback(dialog, new OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() {
                    boolean loginActive = busy && (!loginUrl.isEmpty() || !userCode.isEmpty());
                    if (loggedIn && settingsOpen && !loginActive) showSettings(false);
                    else dialogClose();
                }
            });
            configureWindow();
            root.requestFocus();
            settings.setOnClickListener(v -> showMore());
            closeButton.setOnClickListener(v -> {
                if (loggedIn && settingsOpen) showSettings(false);
                else dialogClose();
            });

            signIn.setOnClickListener(v -> {
                hideKeyboard();
                modelsRequested = false;
                invoke(JugglucoChat::nativeStartLogin);
            });
            signOut.setOnClickListener(v -> {
                modelsRequested = false;
                if (invoke(JugglucoChat::nativeLogout)) question.setText("");
            });
            newChat.setOnClickListener(v -> confirmNewChat());
            chooseModel.setOnClickListener(v -> showModels());
            chooseReasoning.setOnClickListener(v -> showReasoning());
            savedFiles.setOnClickListener(v -> showSavedFiles());
            send.setOnClickListener(v -> sendQuestion());
            openBrowser.setOnClickListener(v -> openLogin());
            copyCode.setOnClickListener(v -> copyLoginCode());
            TextWatcher controls = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) { saveDraft(); updateControls(); }
            };
            question.addTextChangedListener(controls);
            updateControls();
        }

        int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
        LinearLayout.LayoutParams fullWidth() {
            return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        LinearLayout column() {
            LinearLayout out = new LinearLayout(ui);
            out.setOrientation(LinearLayout.VERTICAL);
            return out;
        }
        AppCompatTextView text(String value) {
            AppCompatTextView out = new AppCompatTextView(ui);
            out.setText(value);
            return out;
        }
        AppCompatButton button(String value) {
            AppCompatButton out = new AppCompatButton(ui);
            out.setText(value);
            out.setAllCaps(false);
            out.setMinWidth(0);
            out.setMinimumWidth(0);
            return out;
        }
        void compactFooterButton(AppCompatButton button) {
            // The theme's 48 dp minimum left a large blank band above and
            // below the labels. Keep WRAP_CONTENT for larger font settings.
            button.setMinHeight(dp(32));
            button.setMinimumHeight(0);
            button.setIncludeFontPadding(false);
            button.setPaddingRelative(button.getPaddingStart(), dp(2), button.getPaddingEnd(), dp(2));
        }
        LinearLayout row(View... children) {
            LinearLayout out = new LinearLayout(ui);
            out.setGravity(Gravity.CENTER_VERTICAL);
            for (View child : children) out.addView(child,
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return out;
        }

        void open() {
            dialog.show();
            resizeDialog();
            application.registerActivityLifecycleCallbacks(this);
            application.registerComponentCallbacks(this);
            try {
                String result = nativeInitWithFiles(activity.getNoBackupFilesDir().getAbsolutePath() + "/juggluco-chat",
                        activity.getFilesDir().getAbsolutePath());
                initialized = result == null;
                if (!initialized) localError = result;
            } catch (RuntimeException | LinkageError failure) {
                localError = message(failure);
            }
            if (initialized) {
                running.initialized = true;
                refresh();
                if (running.restoreKeyboard && loggedIn && !settingsOpen) {
                    running.restoreKeyboard = false;
                    question.requestFocus();
                    handler.post(() -> {
                        if (!closed && dialog.getWindow() != null)
                            WindowCompat.getInsetsController(dialog.getWindow(), question).show(WindowInsetsCompat.Type.ime());
                    });
                }
            }
            else {
                status.setText("Chat could not be opened.");
                showError();
                updateControls();
            }
        }

        void refresh() {
            if (!closed && initialized) running.refresh();
        }
        void saveDraft() {
            running.draft = question.getText().toString();
            saveSelection();
        }
        void saveSelection() {
            // A hidden poll may have restored a failed question to the draft
            // before this old window is torn down. Do not overwrite that text.
            if (!TextUtils.equals(question.getText(), running.draft)) return;
            running.selectionStart = Math.max(0, question.getSelectionStart());
            running.selectionEnd = Math.max(0, question.getSelectionEnd());
        }
        void restoreSelection() {
            int length = question.length();
            question.setSelection(Math.min(length, running.selectionStart), Math.min(length, running.selectionEnd));
        }
        void saveScroll() {
            if (restoreScroll) return;
            running.scrollY = transcriptScroll.getScrollY();
            running.followTail = transcript.getHeight() - (running.scrollY + transcriptScroll.getHeight()) <= dp(48);
        }

        void apply(JSONObject snapshot) {
            busy = snapshot.optBoolean("busy", false);
            boolean wasLoggedIn = loggedIn;
            loggedIn = snapshot.optBoolean("logged_in", false);
            if (haveAccountSnapshot && loggedIn != wasLoggedIn) {
                settingsOpen = false;
                running.settingsOpen = false;
                hideKeyboard();
                root.requestFocus();
            }
            haveAccountSnapshot = true;
            serverError = snapshot.optString("error", "");
            String description = snapshot.optString("status", "");
            status.setText(description.isEmpty() ? (loggedIn ? "Signed in" : "Sign in to begin") : description);
            elapsed.setText(duration(snapshot.optLong("elapsed_ms", 0)) + " elapsed");
            StringBuilder activityLines = new StringBuilder();
            JSONArray events = snapshot.optJSONArray("activity");
            if (events != null) for (int i = 0; i < events.length(); ++i) {
                JSONObject entry = events.optJSONObject(i);
                if (entry != null) activityLines.append(duration(entry.optLong("elapsed_ms", 0)))
                        .append("  ").append(entry.optString("text", "")).append("\n\n");
            }
            activityText = activityLines.length() == 0 ? description : activityLines.toString();
            if (activityDetails != null) activityDetails.setText(activityText);
            loginUrl = snapshot.optString("login_url", "");
            userCode = snapshot.optString("user_code", "");
            code.setText(userCode);
            loginLink.setText(loginUrl);
            loginPanel.setVisibility(busy && (!loginUrl.isEmpty() || !userCode.isEmpty())
                    ? View.VISIBLE : View.GONE);
            JSONArray available = snapshot.optJSONArray("models");
            updateModels(available == null ? new JSONArray() : available);
            JSONArray messages = snapshot.optJSONArray("messages");
            if (messages == null) messages = new JSONArray();
            messageCount = messages.length();
            hasPendingQuestion = !snapshot.optString("pending_question", "").isEmpty();
            renderMessages(messages, snapshot.optString("pending_question", ""));
            if (!TextUtils.equals(question.getText(), running.draft)) {
                int start = running.selectionStart, end = running.selectionEnd;
                question.setText(running.draft);
                running.selectionStart = start; running.selectionEnd = end;
                restoreSelection();
            }
            showError();
            updateControls();
            if (resumed && !busy) {
                try {
                    String pending = nativeTakeBrowserFile();
                    if (pending != null) {
                        JSONObject file = new JSONObject(pending);
                        openSavedFile(file.getString("id"), file.getString("file"));
                    }
                } catch (JSONException | RuntimeException | LinkageError failure) {
                    localError = "Could not open saved page: " + message(failure); showError();
                }
            }
            if (resumed && loggedIn && !busy && models.isEmpty() && !modelsRequested) {
                modelsRequested = true;
                invoke(JugglucoChat::nativeLoadModels);
            }
        }

        void updateModels(JSONArray available) {
            String encoded = available.toString();
            if (encoded.equals(modelSnapshot)) return;
            modelSnapshot = encoded;
            models.clear();
            ArrayList<String> ids = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            for (int i = 0; i < available.length(); ++i) {
                JSONObject item = available.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                if (id.isEmpty() || ids.contains(id)) continue;
                String name = item.optString("display_name", "").trim();
                if (name.isEmpty() || !Character.isUpperCase(name.codePointAt(0)) || !name.contains("GPT")) continue;
                String folded = name.toLowerCase(Locale.ROOT);
                if (names.contains(folded)) continue;
                models.add(new Model(id, name, item));
                names.add(folded);
                ids.add(id);
            }
            if (!ids.isEmpty()) {
                modelsRequested = true;
                if (!ids.contains(selectedModelId)) selectedModelId = ids.get(0);
                prefs.edit().putString("model", selectedModelId).apply();
            }
        }

        void renderMessages(JSONArray messages, String pending) {
            String encoded = messages.toString() + "\n" + pending + (busy ? "\nbusy" : "\nidle");
            if (encoded.equals(messageSnapshot)) return;
            messageSnapshot = encoded;
            boolean bottom = forceTranscriptBottom || (restoreScroll ? running.followTail : transcript.getHeight()
                    - (transcriptScroll.getScrollY() + transcriptScroll.getHeight()) <= dp(48));
            final int restoreY = restoreScroll ? running.scrollY : transcriptScroll.getScrollY();
            SpannableStringBuilder text = new SpannableStringBuilder();
            for (int i = 0; i < messages.length(); ++i) {
                JSONObject item = messages.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role");
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                if (text.length() > 0) text.append("\n\n");
                int start = text.length();
                text.append("user".equals(role) ? "You" : answerHeading(
                        item.optString("model_name", ""), item.optString("model_id", "")));
                text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.append("\n");
                final int bodyStart = text.length();
                final String body = item.optString("text", "");
                final Markup.Result formatted = "assistant".equals(role) ? Markup.parse(body) : null;
                text.append(formatted == null ? body : formatted.text);
                if ("assistant".equals(role)) {
                    applyMarkup(text, bodyStart, formatted);
                    linkWebText(text, bodyStart, formatted.text);
                    JSONArray citations = item.optJSONArray("citations");
                    if (citations != null) for (int c = 0; c < Math.min(64, citations.length()); ++c) {
                        JSONObject source = citations.optJSONObject(c);
                        if (source == null) continue;
                        String url = source.optString("url", "");
                        if (!webUrl(url)) continue;
                        int first = source.optInt("start", -1), last = source.optInt("end", -1);
                        int count = body.codePointCount(0, body.length());
                        if (first >= 0 && last > first && last <= count)
                            link(text, bodyStart + formatted.positions[body.offsetByCodePoints(0, first)],
                                    bodyStart + formatted.positions[body.offsetByCodePoints(0, last)], () -> openWebUrl(url));
                        text.append("\n");
                        int sourceStart = text.length();
                        text.append("[").append(Integer.toString(c + 1)).append("] ").append(source.optString("title", url));
                        link(text, sourceStart, text.length(), () -> openWebUrl(url));
                    }
                }
                JSONArray plots = item.optJSONArray("plots");
                if (plots != null) for (int p = 0; p < Math.min(4, plots.length()); ++p) {
                    JSONObject plot = plots.optJSONObject(p);
                    if (plot == null) continue;
                    final int messageIndex = i, plotIndex = p;
                    text.append("\n\n");
                    int linkStart = text.length();
                    text.append("Open plot: ").append(plot.optString("caption", "Plot"));
                    text.setSpan(new ClickableSpan() {
                        @Override public void onClick(View view) { showPlot(messageIndex, plotIndex); }
                        @Override public void updateDrawState(TextPaint paint) {
                            paint.setColor(transcript.getCurrentTextColor());
                            paint.setUnderlineText(true);
                        }
                    }, linkStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                JSONArray files = item.optJSONArray("files");
                if (files != null) for (int f = 0; f < Math.min(4, files.length()); ++f) {
                    JSONObject bundle = files.optJSONObject(f);
                    if (bundle == null) continue;
                    String id = bundle.optString("id", ""), entry = bundle.optString("entrypoint", "jg-files.html");
                    text.append("\n\n");
                    int fileStart = text.length();
                    text.append("Open in Chrome: ").append(bundle.optString("title", "Saved files"));
                    link(text, fileStart, text.length(), () -> openSavedFile(id, entry));
                    text.append("\n"); fileStart = text.length();
                    text.append("View / download files");
                    link(text, fileStart, text.length(), () -> openSavedFile(id, "jg-files.html"));
                    text.append("\n"); fileStart = text.length();
                    text.append("Save as…");
                    link(text, fileStart, text.length(), () -> showExportFiles(bundle));
                }
            }
            if (!pending.isEmpty()) {
                if (text.length() > 0) text.append("\n\n");
                int start = text.length();
                text.append("You");
                text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.append("\n").append(pending).append("\n\n");
                text.append(busy ? "Answer in progress…" : "No answer was completed. Send the question again to retry.");
            }
            if (text.length() == 0) text.append("Your conversation will appear here.");
            transcript.setText(text);
            handler.post(() -> {
                if (!closed) {
                    if (bottom) transcriptScroll.fullScroll(View.FOCUS_DOWN);
                    else transcriptScroll.scrollTo(0, restoreY);
                }
            });
            restoreScroll = false;
            forceTranscriptBottom = false;
        }

        void applyMarkup(SpannableStringBuilder text, int offset, Markup.Result result) {
            for (Markup.Mark mark : result.marks) {
                if (mark.end <= mark.start) continue;
                int start = offset + mark.start, end = offset + mark.end;
                if (mark.kind == Markup.LINK) {
                    if (webUrl(mark.url)) link(text, start, end, () -> openWebUrl(mark.url));
                    continue;
                }
                Object span = mark.kind == Markup.CODE ? new TypefaceSpan("monospace") :
                        mark.kind == Markup.QUOTE ? new QuoteSpan(transcript.getCurrentTextColor()) :
                        new StyleSpan(mark.kind == Markup.ITALIC ? Typeface.ITALIC :
                                mark.kind == Markup.BOTH ? Typeface.BOLD_ITALIC : Typeface.BOLD);
                text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (mark.kind == Markup.HEADING)
                    text.setSpan(new RelativeSizeSpan(1.12f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        void link(SpannableStringBuilder text, int start, int end, Runnable action) {
            if (end <= start) return;
            text.setSpan(new ClickableSpan() {
                @Override public void onClick(View view) { action.run(); }
                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(transcript.getCurrentTextColor()); paint.setUnderlineText(true);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        boolean webUrl(String url) {
            Uri uri = Uri.parse(url);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        }
        void linkWebText(SpannableStringBuilder text, int offset, String body) {
            Matcher found = Pattern.compile("https?://[^\\s<>\\\"\\]\\)]+").matcher(body);
            while (found.find()) {
                int end = found.end();
                while (end > found.start() && ".,;".indexOf(body.charAt(end - 1)) >= 0) --end;
                final String url = body.substring(found.start(), end);
                if (webUrl(url)) link(text, offset + found.start(), offset + end, () -> openWebUrl(url));
            }
        }
        void openWebUrl(String url) {
            if (!webUrl(url)) return;
            hideKeyboard();
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
                try { activity.startActivity(new Intent(intent).setPackage("com.android.chrome")); }
                catch (ActivityNotFoundException unavailable) { activity.startActivity(intent); }
            } catch (RuntimeException failure) {
                // Do not include the URL: local URLs can contain an API secret.
                localError = "Could not open a browser."; showError();
            }
        }
        void openSavedFile(String id, String name) {
            try {
                JSONObject result = new JSONObject(nativeFileUrl(id, name));
                if (result.has("error")) { localError = result.getString("error"); showError(); return; }
                String url = result.getString("url");
                if (!"127.0.0.1".equals(Uri.parse(url).getHost())) throw new JSONException("Invalid local file URL");
                openWebUrl(url);
            } catch (JSONException | RuntimeException | LinkageError failure) {
                localError = "Could not open the saved file."; showError();
            }
        }
        void showSavedFiles() {
            dismissChild();
            try {
                JSONObject result = new JSONObject(nativeListFiles());
                if (result.has("error")) { localError = result.getString("error"); showError(); return; }
                JSONArray files = result.getJSONArray("files");
                if (files.length() == 0) {
                    childDialog = new AlertDialog.Builder(ui).setTitle("Saved files")
                            .setMessage("Ask for an HTML/JavaScript page, CSV, JSON or text file. Saved files remain available after New chat or Sign out.")
                            .setPositiveButton("Close", null).create();
                } else {
                    String[] labels = new String[files.length()];
                    for (int i = 0; i < labels.length; ++i) {
                        JSONObject bundle = files.getJSONObject(i);
                        String id = bundle.optString("id", "");
                        labels[i] = bundle.optString("title", "Files") + " · " + id.substring(0, Math.min(6, id.length()));
                    }
                    childDialog = new AlertDialog.Builder(ui).setTitle("Saved files")
                            .setItems(labels, (dialog, which) -> {
                                JSONObject bundle = files.optJSONObject(which);
                                if (bundle != null) showFileActions(bundle);
                            }).setNegativeButton("Close", null).create();
                }
                childDialog.show();
            } catch (JSONException | RuntimeException | LinkageError failure) {
                localError = "Could not list saved files."; showError();
            }
        }
        void showFileActions(JSONObject bundle) {
            dismissChild();
            final String id = bundle.optString("id", "");
            childDialog = new AlertDialog.Builder(ui).setTitle(bundle.optString("title", "Files"))
                    .setMessage("Save as lets you choose a file and its destination in Android. Open uses Juggluco's web server. Delete saved folders under Web server → Upload web pages → chatgpt.")
                    .setPositiveButton("Open", (d, w) -> openSavedFile(id, bundle.optString("entrypoint", "jg-files.html")))
                    .setNeutralButton("Save as…", (d, w) -> showExportFiles(bundle))
                    .setNegativeButton("Close", null).create();
            childDialog.show();
        }

        void showExportFiles(JSONObject bundle) {
            dismissChild();
            final ArrayList<String> names = new ArrayList<>();
            JSONArray files = bundle.optJSONArray("files");
            boolean hasHtml = false;
            if (files != null) for (int i = 0; i < Math.min(8, files.length()); ++i) {
                JSONObject item = files.optJSONObject(i);
                String name = item == null ? "" : item.optString("name", "");
                if (!name.isEmpty()) {
                    names.add(name);
                    hasHtml |= name.endsWith(".html") || name.endsWith(".htm");
                }
            }
            if (hasHtml) names.add("jg-api.js");
            if (names.isEmpty()) { localError = "No generated files are available."; showError(); return; }
            final String id = bundle.optString("id", "");
            if (names.size() == 1) { saveDocument(id, names.get(0)); return; }
            childDialog = new AlertDialog.Builder(ui).setTitle("Save a file as…")
                    .setItems(names.toArray(new String[0]), (ignored, which) -> saveDocument(id, names.get(which)))
                    .setNegativeButton("Cancel", null).create();
            childDialog.show();
        }
        @SuppressWarnings("deprecation")
        void saveDocument(String id, String name) {
            if (closed || !resumed) return;
            hideKeyboard();
            try {
                FragmentManager manager = activity.getFragmentManager();
                if (manager.findFragmentByTag(SaveFileFragment.TAG) != null) {
                    localError = "A file save is already in progress."; showError(); return;
                }
                SaveFileFragment fragment = new SaveFileFragment();
                Bundle args = new Bundle(); args.putString("id", id); args.putString("file", name);
                fragment.setArguments(args);
                manager.beginTransaction().add(fragment, SaveFileFragment.TAG).commit();
                manager.executePendingTransactions();
                localError = ""; showError();
            } catch (RuntimeException failure) {
                localError = "Could not start Save as. Return to the chat and try again."; showError();
            }
        }

        void updateControls() {
            if (closed) return;
            boolean loginActive = busy && (!loginUrl.isEmpty() || !userCode.isEmpty());
            boolean accountPage = !loggedIn || settingsOpen || loginActive;
            chatPanel.setVisibility(accountPage ? View.GONE : View.VISIBLE);
            settingsScroll.setVisibility(accountPage ? View.VISIBLE : View.GONE);
            modelPanel.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
            settings.setVisibility((loggedIn && !accountPage) || busy ? View.VISIBLE : View.GONE);
            settings.setEnabled(initialized);
            closeButton.setText(accountPage ? "Close" : "Juggluco");
            progressRow.setVisibility(busy || !loggedIn ? View.VISIBLE : View.GONE);
            thinking.setVisibility(busy ? View.VISIBLE : View.GONE);
            elapsed.setVisibility(busy ? View.VISIBLE : View.GONE);
            newChat.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
            signIn.setText(loggedIn ? "Sign in again" : "Sign in");
            signIn.setEnabled(initialized && !busy);
            signOut.setEnabled(initialized && !busy);
            newChat.setEnabled(initialized && !busy);
            chooseModel.setEnabled(initialized && loggedIn && !busy);
            chooseReasoning.setEnabled(initialized && loggedIn && !busy);
            internet.setEnabled(initialized && loggedIn && !busy);
            savedFiles.setEnabled(initialized && !busy);
            Model selected = selectedModel();
            chooseModel.setText(selected == null ? "Select a model" : selected.name.isEmpty() ? selected.id : selected.name);
            chooseReasoning.setText("Reasoning: " + effortLabel(selectedEffort()));
            question.setEnabled(initialized && loggedIn);
            String hint = selected == null
                    ? "Choose a model in Settings, then ask a question" : "Ask about your Juggluco data";
            if (!TextUtils.equals(question.getHint(), hint)) question.setHint(hint);
            send.setEnabled(initialized && loggedIn && !busy
                    && selected != null
                    && !question.getText().toString().trim().isEmpty());
            openBrowser.setEnabled(busy && allowedLoginUrl(loginUrl));
            copyCode.setEnabled(busy && !userCode.isEmpty());
        }
        void showMore() {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            if (loggedIn && !settingsOpen) { labels.add("Settings"); actions.add(() -> showSettings(true)); }
            labels.add("Activity"); actions.add(this::showActivity);
            if (messageCount > 0 || hasPendingQuestion) {
                labels.add("Save chat…"); actions.add(() -> chooseChatExport(false));
                labels.add("Share chat…"); actions.add(() -> chooseChatExport(true));
            }
            if (loggedIn && !busy) { labels.add("Clear analysis memory"); actions.add(this::confirmClearAnalysis); }
            if (busy) {
                labels.add("Stop request");
                actions.add(() -> { running.releaseWake(); invoke(JugglucoChat::nativeCancel); });
            }
            dismissChild();
            childDialog = new AlertDialog.Builder(ui).setItems(labels.toArray(new String[0]),
                    (d, which) -> actions.get(which).run()).setNegativeButton("Close", null).create();
            childDialog.show();
        }
        void chooseChatExport(boolean share) {
            dismissChild();
            childDialog = new AlertDialog.Builder(ui).setTitle(share ? "Share chat" : "Save chat")
                    .setItems(new String[]{"HTML · conversation and plots", "Text · conversation without images"},
                            (ignored, which) -> exportDocument(share, which == 0 ? "html" : "txt", null))
                    .setNegativeButton("Cancel", null).create();
            childDialog.show();
        }
        @SuppressWarnings("deprecation")
        void exportDocument(boolean share, String format, String svg) {
            if (closed || !resumed) return;
            hideKeyboard();
            try {
                FragmentManager manager = activity.getFragmentManager();
                if (manager.findFragmentByTag(SaveFileFragment.TAG) != null) {
                    localError = "A file export is already in progress."; showError(); return;
                }
                Bundle args = new Bundle(); args.putString("format", format); args.putBoolean("share", share);
                if (svg != null) args.putString("svg", svg);
                SaveFileFragment fragment = new SaveFileFragment(); fragment.setArguments(args);
                manager.beginTransaction().add(fragment, SaveFileFragment.TAG).commit();
                manager.executePendingTransactions();
                localError = ""; showError();
            } catch (RuntimeException failure) {
                localError = "Could not start the export."; showError();
            }
        }
        void showError() {
            String value = !localError.isEmpty() ? localError : serverError;
            error.setText(value.isEmpty() ? "" : "Error: " + value);
            error.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }
        String message(Throwable failure) {
            String value = failure.getMessage();
            return value == null || value.isEmpty() ? failure.getClass().getSimpleName() : value;
        }

        boolean invoke(NativeCall call) {
            if (closed || !initialized) return false;
            try {
                String result = call.run();
                localError = result == null ? "" : result;
                if (result != null) { showError(); return false; }
                refresh();
                return true;
            } catch (RuntimeException | LinkageError failure) {
                localError = message(failure);
                showError();
                return false;
            }
        }
        void sendQuestion() {
            String draft = question.getText().toString().trim();
            Model selected = selectedModel();
            String effort = selectedEffort();
            if (closed || !initialized || draft.isEmpty() || selected == null || busy || !loggedIn) return;
            try {
                running.publishPhoneActivity();
                String result = nativeSendWithOptions(draft, selected.id, effort, internet.isChecked());
                localError = result == null ? "" : result;
                if (result != null) { showError(); return; }
                // The native snapshot owns the submitted question, including
                // while this view is absent. The editor is the next draft.
                question.setText("");
                forceTranscriptBottom = true;
                hideKeyboard(); root.requestFocus();
                refresh();
            } catch (RuntimeException | LinkageError failure) {
                localError = message(failure); showError();
            }
        }

        void confirmNewChat() {
            if (messageCount == 0) { startNewChat(); return; }
            dismissChild();
            childDialog = new AlertDialog.Builder(ui)
                    .setTitle("New chat")
                    .setMessage("Start a new conversation? Saved results, working notes and earlier questions remain available to the assistant. Use Clear analysis memory in More to forget them.")
                    .setPositiveButton("New chat", (ignored, which) -> startNewChat())
                    .setNegativeButton("Cancel", null).create();
            childDialog.show();
        }
        void startNewChat() {
            if (invoke(JugglucoChat::nativeNewChat)) {
                question.setText("");
                showSettings(false);
            }
        }
        void confirmClearAnalysis() {
            dismissChild();
            childDialog = new AlertDialog.Builder(ui)
                    .setTitle("Clear analysis memory")
                    .setMessage("Delete saved analysis results, notes and archived conversations, and start a new chat? Juggluco records and generated files remain on the phone. The assistant will no longer have the old file references.")
                    .setPositiveButton("Clear", (ignored, which) -> {
                        if (invoke(JugglucoChat::nativeClearAnalysis)) { question.setText(""); showSettings(false); }
                    }).setNegativeButton("Cancel", null).create();
            childDialog.show();
        }
        void showModels() {
            dismissChild();
            AlertDialog.Builder builder = new AlertDialog.Builder(ui).setTitle("Models")
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Reload", (ignored, which) -> {
                        modelsRequested = true;
                        invoke(JugglucoChat::nativeLoadModels);
                    });
            if (models.isEmpty()) {
                builder.setMessage("No capitalized GPT model names are available. Reload the model list.");
            } else {
                final ArrayList<Model> choices = new ArrayList<>(models);
                String[] labels = new String[choices.size()];
                for (int i = 0; i < labels.length; ++i) labels[i] = choices.get(i).label();
                int checked = -1;
                for (int i = 0; i < choices.size(); ++i) if (choices.get(i).id.equals(selectedModelId)) checked = i;
                builder.setSingleChoiceItems(labels, checked, (whichDialog, index) -> {
                    if (closed) return;
                    selectedModelId = choices.get(index).id;
                    prefs.edit().putString("model", selectedModelId).apply();
                    updateControls();
                    whichDialog.dismiss();
                });
            }
            childDialog = builder.create();
            childDialog.show();
        }

        Model selectedModel() {
            for (Model item : models) if (item.id.equals(selectedModelId)) return item;
            return null;
        }
        String selectedEffort() {
            Model item = selectedModel();
            if (item == null) return "";
            String saved = prefs.getString("reasoning:" + item.id, null);
            if (saved == null) return item.recommendedEffort;
            return saved.isEmpty() || item.efforts.contains(saved) ? saved : "";
        }
        String effortLabel(String effort) {
            switch (effort) {
                case "none": return "None";
                case "minimal": return "Minimal";
                case "low": return "Low";
                case "medium": return "Medium";
                case "high": return "High";
                case "xhigh": return "Extra high";
                case "max": return "Max";
                default: return "Server default";
            }
        }
        void showReasoning() {
            dismissChild();
            Model item = selectedModel();
            AlertDialog.Builder builder = new AlertDialog.Builder(ui).setTitle("Reasoning effort")
                    .setNegativeButton("Close", null);
            if (item == null || item.efforts.isEmpty()) {
                builder.setMessage("No reasoning options are available for this model. "
                        + "The server default will be used. Reload Models to check available options.");
            } else {
                final String modelId = item.id;
                final ArrayList<String> choices = new ArrayList<>();
                choices.add("");
                choices.addAll(item.efforts);
                String[] labels = new String[choices.size()];
                for (int i = 0; i < labels.length; ++i) labels[i] = effortLabel(choices.get(i));
                builder.setSingleChoiceItems(labels, choices.indexOf(selectedEffort()), (whichDialog, index) -> {
                    if (closed) return;
                    prefs.edit().putString("reasoning:" + modelId, choices.get(index)).apply();
                    updateControls();
                    whichDialog.dismiss();
                });
            }
            childDialog = builder.create();
            childDialog.show();
        }
        void dismissChild() {
            if (childDialog != null) { childDialog.dismiss(); childDialog = null; }
            activityDetails = null;
            if (chartDialog != null) { chartDialog.dismiss(); chartDialog = null; }
        }

        String duration(long millis) {
            long seconds = Math.max(0, millis / 1000);
            return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
        }
        void showActivity() {
            dismissChild();
            activityDetails = text(activityText);
            activityDetails.setTextIsSelectable(true);
            activityDetails.setPadding(dp(12), dp(8), dp(12), dp(8));
            NestedScrollView scroll = new NestedScrollView(ui);
            scroll.addView(activityDetails, fullWidth());
            childDialog = new AlertDialog.Builder(ui).setTitle("Current activity")
                    .setView(scroll).setNegativeButton("Close", null).create();
            childDialog.setOnDismissListener(ignored -> activityDetails = null);
            childDialog.show();
        }
        void showPlot(int messageIndex, int plotIndex) {
            dismissChild();
            hideKeyboard();
            try {
                String svg = nativeGetPlot(messageIndex, plotIndex);
                if (svg == null || !svg.startsWith("<svg ") || svg.length() > 131072)
                    throw new IllegalStateException("The plot is unavailable.");
                final String originalSvg = svg;
                if (ColorUtils.calculateLuminance(backgroundColor) <= 0.5)
                    svg = svg.replace("class=\"jg-plot\"", "class=\"jg-plot dark\"");
                final WebView image = new WebView(ui);
                image.setBackgroundColor(backgroundColor);
                image.getSettings().setJavaScriptEnabled(false);
                image.getSettings().setAllowFileAccess(false);
                image.getSettings().setAllowContentAccess(false);
                image.getSettings().setBlockNetworkLoads(true);
                image.getSettings().setBuiltInZoomControls(true);
                image.getSettings().setDisplayZoomControls(false);
                image.getSettings().setUseWideViewPort(true);
                image.getSettings().setLoadWithOverviewMode(true);
                image.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return true; }
                });
                final AppCompatDialog graph = new AppCompatDialog(ui);
                chartDialog = graph;
                graph.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
                LinearLayout content = column();
                content.setBackgroundColor(backgroundColor);
                LinearLayout toolbar = new LinearLayout(ui);
                toolbar.setGravity(Gravity.CENTER_VERTICAL);
                toolbar.addView(text("Plot · pinch to zoom"),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                AppCompatButton more = button("More");
                more.setOnClickListener(v -> {
                    if (childDialog != null) childDialog.dismiss();
                    childDialog = new AlertDialog.Builder(ui)
                            .setItems(new String[]{"Save plot…", "Share plot…"}, (ignored, which) -> {
                                graph.dismiss(); exportDocument(which == 1, "svg", originalSvg);
                            }).setNegativeButton("Cancel", null).create();
                    childDialog.show();
                });
                toolbar.addView(more);
                AppCompatButton close = button("Close");
                close.setOnClickListener(v -> graph.dismiss());
                toolbar.addView(close);
                content.addView(toolbar, fullWidth());
                content.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
                graph.setContentView(content);
                graph.setOnDismissListener(ignored -> {
                    image.stopLoading(); image.destroy();
                    if (chartDialog == graph) chartDialog = null;
                });
                graph.show();
                Window window = graph.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(backgroundColor));
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    WindowCompat.setDecorFitsSystemWindows(window, false);
                    boolean light = ColorUtils.calculateLuminance(backgroundColor) > 0.5;
                    WindowCompat.getInsetsController(window, content).setAppearanceLightStatusBars(light);
                    WindowCompat.getInsetsController(window, content).setAppearanceLightNavigationBars(light);
                }
                ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                    view.setPadding(bars.left + dp(8), bars.top, bars.right + dp(8), bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
                ViewCompat.requestApplyInsets(content);
                // A local SVG image, not model-authored HTML or a remote page.
                image.loadDataWithBaseURL(null, "<!doctype html><html><head><meta name=\"viewport\" "
                        + "content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" "
                        + "content=\"default-src 'none'; style-src 'unsafe-inline'\">"
                        + "<style>html,body{margin:0;height:100%;}svg{width:100%;height:100%;display:block;}</style>"
                        + "</head><body>" + svg + "</body></html>", "text/html", "UTF-8", null);
            } catch (RuntimeException | LinkageError failure) {
                dismissChild(); localError = "Could not display plot: " + message(failure); showError();
            }
        }

        void showSettings(boolean show) {
            hideKeyboard();
            root.requestFocus();
            settingsOpen = show;
            running.settingsOpen = show;
            updateControls();
        }
        void hideKeyboard() {
            Window window = dialog.getWindow();
            if (window != null) WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime());
        }

        boolean allowedLoginUrl(String value) {
            if (value == null || value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return false;
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "auth.openai.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("/codex/device".equals(uri.getPath()) || "/codex/device/".equals(uri.getPath()))
                    && uri.getQuery() == null && uri.getFragment() == null;
        }
        void openLogin() {
            if (!allowedLoginUrl(loginUrl)) {
                localError = "The sign-in address is not the expected OpenAI page.";
                showError();
                return;
            }
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)));
            } catch (RuntimeException failure) {
                localError = "Could not open the sign-in page: " + message(failure);
                showError();
            }
        }
        void copyLoginCode() {
            if (userCode.isEmpty()) return;
            try {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                ClipData clip = ClipData.newPlainText("OpenAI one-time code", userCode);
                if (Build.VERSION.SDK_INT >= 24) {
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                    clip.getDescription().setExtras(extras);
                }
                clipboard.setPrimaryClip(clip);
            } catch (RuntimeException failure) {
                localError = "Could not copy the code: " + message(failure);
                showError();
            }
        }

        void dialogClose() {
            hideKeyboard();
            running.restoreKeyboard = false;
            running.shortcutWanted = running.shortcutEnabled();
            // Dismiss only the chat. Juggluco owns the underlying screen and
            // its back stack; never discard unfinished settings on that stack.
            dialog.dismiss();
            running.showShortcut(activity);
        }
        void close() {
            if (closed) return;
            saveSelection(); saveScroll();
            running.settingsOpen = settingsOpen;
            closed = true;
            handler.removeCallbacksAndMessages(null);
            if (chatSurface.getViewTreeObserver().isAlive())
                chatSurface.getViewTreeObserver().removeOnGlobalLayoutListener(chatGeometryListener);
            application.unregisterActivityLifecycleCallbacks(this);
            application.unregisterComponentCallbacks(this);
            dismissChild();
            if (current.get() == this) current.clear();
            running.refresh();
        }
        void configureWindow() {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setBackgroundDrawable(new ColorDrawable(backgroundColor));
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(backgroundColor);
            window.setNavigationBarColor(backgroundColor);
            WindowCompat.setDecorFitsSystemWindows(window, false);
            WindowManager.LayoutParams attributes = window.getAttributes();
            if (Build.VERSION.SDK_INT >= 30) {
                // Neither WindowManager nor our content fits the keyboard.
                // The full-height chat stays behind it, just like an overlay.
                attributes.setFitInsetsTypes(0);
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= 28) {
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            window.setAttributes(attributes);
            boolean light = ColorUtils.calculateLuminance(backgroundColor) > 0.5;
            WindowCompat.getInsetsController(window, root).setAppearanceLightStatusBars(light);
            WindowCompat.getInsetsController(window, root).setAppearanceLightNavigationBars(light);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            View decor = window.getDecorView();
            for (View container = root; container != decor; ) {
                container.setFitsSystemWindows(false);
                if (!(container.getParent() instanceof View)) break;
                container = (View) container.getParent();
            }
            chatSurface.getViewTreeObserver().addOnGlobalLayoutListener(chatGeometryListener);
            ViewCompat.setOnApplyWindowInsetsListener(chatSurface, (view, insets) -> {
                // Post until the new insets and rotated window frame agree.
                view.post(this::applyChatGeometry);
                return WindowInsetsCompat.CONSUMED;
            });
        }
        void applyChatGeometry() {
            Window window = dialog.getWindow();
            if (closed || window == null || chatSurface.getWidth() == 0) return;
            View decor = window.getDecorView();
            decor.getLocationOnScreen(chatScreenPosition);
            chatDecorBounds.set(chatScreenPosition[0], chatScreenPosition[1],
                    chatScreenPosition[0] + decor.getWidth(), chatScreenPosition[1] + decor.getHeight());
            root.getLocationOnScreen(chatScreenPosition);
            chatContentBounds.set(chatScreenPosition[0], chatScreenPosition[1],
                    chatScreenPosition[0] + root.getWidth(), chatScreenPosition[1] + root.getHeight());
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
            if (insets == null) return;
            Insets bars;
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics metrics = window.getWindowManager().getCurrentWindowMetrics();
                chatWindowBounds.set(metrics.getBounds());
                bars = WindowInsetsCompat.toWindowInsetsCompat(metrics.getWindowInsets())
                        .getInsets(WindowInsetsCompat.Type.systemBars());
            } else {
                chatWindowBounds.set(chatDecorBounds);
                bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            }
            // Exclude actual system bars only, preserving the one-sided
            // landscape navigation margin. Never include IME in root padding.
            chatAvailableBounds.set(chatWindowBounds.left + bars.left, chatWindowBounds.top + bars.top,
                    chatWindowBounds.right - bars.right, chatWindowBounds.bottom - bars.bottom);
            int left = dp(8) + Math.max(0, chatAvailableBounds.left - chatContentBounds.left);
            int top = Math.max(0, chatAvailableBounds.top - chatContentBounds.top);
            int right = dp(8) + Math.max(0, chatContentBounds.right - chatAvailableBounds.right);
            int bottom = Math.max(0, chatContentBounds.bottom - chatAvailableBounds.bottom);
            if (root.getPaddingLeft() != left || root.getPaddingTop() != top
                    || root.getPaddingRight() != right || root.getPaddingBottom() != bottom)
                root.setPadding(left, top, right, bottom);
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0;
            int keyboardTop = chatDecorBounds.bottom - ime.bottom;
            if (Build.VERSION.SDK_INT < 30 && !visible) {
                // Some older keyboards expose their edge only as a visible frame.
                root.getWindowVisibleDisplayFrame(chatVisibleBounds);
                if (!chatVisibleBounds.isEmpty() && chatDecorBounds.contains(chatVisibleBounds)
                        && chatVisibleBounds.bottom < chatAvailableBounds.bottom - dp(80)) {
                    visible = true;
                    keyboardTop = chatVisibleBounds.bottom;
                }
            }
            keyboardDone.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) {
                chatSurface.getLocationOnScreen(chatScreenPosition);
                float x = Math.max(left, Math.min(chatAvailableBounds.right, chatDecorBounds.right)
                        - chatScreenPosition[0] - dp(8) - keyboardDone.getMeasuredWidth());
                float y = Math.max(top, keyboardTop - chatScreenPosition[1] - keyboardDone.getMeasuredHeight());
                keyboardDone.setX(x);
                keyboardDone.setY(y);
            }
            String report = "chat geometry overlay v1: decor=" + chatDecorBounds
                    + " content=" + chatContentBounds + " bars=" + bars + " ime=" + ime
                    + " padding=" + left + "," + top + "," + right + "," + bottom
                    + " keyboardVisible=" + visible + " keyboardTop=" + keyboardTop
                    + " done=" + keyboardDone.getX() + "," + keyboardDone.getY()
                    + "," + keyboardDone.getWidth() + "," + keyboardDone.getHeight();
            if (!report.equals(lastGeometryReport)) {
                Log.i("JugglucoChat", report);
                lastGeometryReport = report;
            }
        }
        void resizeDialog() {
            Window window = dialog.getWindow();
            if (window == null || closed) return;
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            ViewCompat.requestApplyInsets(chatSurface);
            if (chartDialog != null && chartDialog.getWindow() != null) {
                Window graph = chartDialog.getWindow();
                graph.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                ViewCompat.requestApplyInsets(graph.getDecorView());
            }
        }
        @Override public void onConfigurationChanged(Configuration configuration) {
            if (!closed) resizeDialog();
        }
        @Override public void onLowMemory() {}
        @Override public void onActivityResumed(Activity target) {
            if (target == activity && !closed) { resumed = true; resizeDialog(); refresh(); }
        }
        @Override public void onActivityPaused(Activity target) {
            if (target == activity) {
                saveSelection(); saveScroll();
                resumed = false;
                handler.removeCallbacksAndMessages(null);
                // Monitoring belongs to the application, including while a
                // browser, document picker or Juggluco curve is on screen.
                running.refresh();
            }
        }
        @Override public void onActivityDestroyed(Activity target) {
            if (target == activity) {
                if (!closed && activity.isChangingConfigurations()) {
                    running.reopenAfterRotation = true;
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
                    running.restoreKeyboard = question.hasFocus() && insets != null
                            && insets.isVisible(WindowInsetsCompat.Type.ime());
                }
                close();
                if (dialog.isShowing()) dialog.dismiss();
            }
        }
        @Override public void onActivityCreated(Activity target, Bundle state) {}
        @Override public void onActivityStarted(Activity target) {}
        @Override public void onActivityStopped(Activity target) {}
        @Override public void onActivitySaveInstanceState(Activity target, Bundle state) {}
    }


}
