/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.google.android.gms.wearable.Wearable;
import java.util.*;

/** The same scrollable controls run on phones and Wear OS. */
public final class Libre3EmulatorActivity extends Activity {
    private LinearLayout form,advanced;
    private ScrollView scroll;
    private EditText name,serial,pin,start,warmup,wear,state,glucose,amplitude,period,interval,address,certificate;
    private CheckBox unused,manual;
    private Spinner host,source;
    private TextView status;
    private final List<String> nodeIds=new ArrayList<>(),nodeNames=new ArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Libre3EmulatorConfig config;
    private Libre3EmulatorAddress scanner;
    private Libre3EmulatorAddress.Reporter reporter;
    private Runnable permissionAction;
    private String note="";
    private String helperAddress="";
    private final Runnable update=new Runnable() {
        public void run() {
            // Updating a selectable TextView can ask its ScrollView to reveal
            // the selection again. Leave the form alone while the user is
            // scrolling, editing or selecting; refresh on return to the top.
            if(scroll.getScrollY()==0 && !(getCurrentFocus() instanceof EditText) && !status.hasSelection())
                refreshStatus();
            main.postDelayed(this,1000);
        }
    };
    private void refreshStatus() {
        Libre3EmulatorConfig current=config.copy(); current.targetNode=selectedNode();
        String observed=Libre3Emulator.currentAddress(current);
        String entered=text(address),selected=manual.isChecked() || current.targetNode.equals("external")?entered:observed;
        long age=Libre3Emulator.addressAgeSeconds(current);
        String warning="";
        if(!current.targetNode.equals("external") && manual.isChecked()) {
            if(observed.isEmpty()) warning="\nEntered address is unverified. The Android settings address may differ from the advertising address.";
            else if(!entered.equalsIgnoreCase(observed)) warning="\nEntered address differs from observed address.";
        }
        if(!Libre3Emulator.nfcAddress.isEmpty() && !observed.isEmpty() && !Libre3Emulator.nfcAddress.equalsIgnoreCase(observed))
            warning+="\nActive NFC address differs from observed address. Stop NFC and restart it with the discovered address.";
        String message=Libre3Emulator.currentStatus(current)+"\n"+Libre3Emulator.nfcStatus+
            "\n"+Libre3Emulator.currentDiscovery(current)+"\nObserved address of selected host: "+(observed.isEmpty()?"unknown":observed)+
            (age<0?"":" ("+age+"s ago)")+"\nNFC selection: "+(selected.isEmpty()?"unknown":selected)+warning+
            (helperAddress.isEmpty()?"":"\nOther emulator found: "+helperAddress)+
            (note.isEmpty()?"":"\n"+note);
        if(!message.contentEquals(status.getText())) status.setText(message);
    }
    @Override public void onCreate(Bundle saved) {
        setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        super.onCreate(saved); setTitle(R.string.l3emu_title);
        if(Build.VERSION.SDK_INT<26 || BuildConfig.libreVersion!=3) {
            new AlertDialog.Builder(this).setMessage("Libre 3 and Android 8 or newer are required")
                .setPositiveButton(android.R.string.ok,(d,w)->finish()).setOnCancelListener(d->finish()).show(); return;
        }
        try {
            byte[] edit=saved==null?null:saved.getByteArray("config");
            config=edit==null?Libre3Emulator.load(this,Libre3Emulator.prefs(this).getString("selected","Default")):
                Libre3EmulatorConfig.decode(edit);
            if(Applic.isWearable && Libre3Emulator.running!=null) { config=Libre3Emulator.running.copy(); config.targetNode=""; }
        } catch(Exception error) { Libre3EmulatorLog.error("UI","profile restore failed",error); config=new Libre3EmulatorConfig(); }
        Libre3EmulatorLog.i("UI","opened sdk="+Build.VERSION.SDK_INT+" wearable="+Applic.isWearable+" "+Libre3EmulatorLog.profile(config));
        scroll=new ScrollView(this); scroll.setFillViewport(true);
        form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(Applic.isWearable?24:16); form.setPadding(pad,pad,pad,dp(32));
        scroll.addView(form); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        button(R.string.l3emu_find,()->permissions(this::find));
        label(R.string.l3emu_intro); status=label(0); status.setTextIsSelectable(true);
        name=field(R.string.l3emu_profile,false);
        button(R.string.l3emu_save,()->{ config=read(); Libre3Emulator.save(this,config); toast(R.string.l3emu_saved); });
        button(R.string.l3emu_load,this::chooseProfile);
        label(R.string.l3emu_host); host=new Spinner(this); form.addView(host);
        button(R.string.l3emu_refresh,this::refreshNodes);
        button(R.string.l3emu_real,()->{ config=Libre3Emulator.realSensor(this,read()); fill(); });
        serial=field(R.string.l3emu_serial,false); pin=field(R.string.l3emu_pin,false);
        unused=check(R.string.l3emu_unused);
        start=field(R.string.l3emu_time,true);
        button(R.string.l3emu_now,()->start.setText(Long.toString(System.currentTimeMillis()/1000L)));
        warmup=field(R.string.l3emu_warmup,true); wear=field(R.string.l3emu_wear,true); state=field(R.string.l3emu_state,true);
        label(R.string.l3emu_source); source=new Spinner(this);
        source.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{
            getString(R.string.l3emu_fixed),getString(R.string.l3emu_wave),getString(R.string.l3emu_latest)})); form.addView(source);
        glucose=field(R.string.l3emu_glucose,true); amplitude=field(R.string.l3emu_amplitude,true);
        period=field(R.string.l3emu_period,true); interval=field(R.string.l3emu_interval,true);
        address=field(R.string.l3emu_address,false); manual=check(R.string.l3emu_manual);
        label(R.string.l3emu_address_help);
        button(R.string.l3emu_discover,()->permissions(()->{
            config=read(); Libre3Emulator.save(this,config); Libre3EmulatorWear.rediscover(this,config);
        }));
        button(R.string.l3emu_copy_address,()->{
            String observed=Libre3Emulator.currentAddress(read());
            if(observed.isEmpty()) throw new IllegalStateException(getString(R.string.l3emu_no_address));
            address.setText(observed); manual.setChecked(false);
        });
        button(R.string.l3emu_start,()->permissions(()->{ config=read(); Libre3Emulator.start(this,config); start.setText(Long.toString(config.startTime)); }));
        button(R.string.l3emu_stop,()->{
            Libre3EmulatorConfig selected=config.copy(); selected.targetNode=selectedNode(); Libre3Emulator.stop(this,selected);
        });
        button(R.string.l3emu_nfc_start,()->{
            config=read(); Libre3Emulator.save(this,config);
            Libre3Emulator.startNfc(getApplicationContext(),config,manual.isChecked());
        });
        button(R.string.l3emu_nfc_stop,Libre3Emulator::stopNfc);
        button(R.string.l3emu_advanced,()->advanced.setVisibility(advanced.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        advanced=new LinearLayout(this); advanced.setOrientation(LinearLayout.VERTICAL); advanced.setVisibility(View.GONE);
        LinearLayout outer=form; form=advanced;
        label(R.string.l3emu_cert_help); certificate=field(R.string.l3emu_certificate,false);
        certificate.setSingleLine(false); certificate.setMinLines(3); certificate.setMaxLines(6);
        button(R.string.l3emu_reset,()->{
            Libre3EmulatorConfig cfg=read();
            if(Libre3Emulator.nfc!=null || (cfg.targetNode.isEmpty()?Libre3Emulator.running!=null:Libre3Emulator.remoteReady))
                throw new IllegalStateException("Stop NFC and Bluetooth before resetting pairing");
            new Libre3Emulator.Store(this,cfg).reset();
            if(!cfg.targetNode.isEmpty() && !cfg.targetNode.equals("external")) Libre3EmulatorWear.send(this,cfg.targetNode,"reset",cfg.encode());
            toast(R.string.l3emu_reset_done);
        });
        form=outer; form.addView(advanced); button(R.string.l3emu_close,this::finish);
        fill(); refreshNodes(); refreshStatus();
    }
    private int dp(int value) { return (int)(getResources().getDisplayMetrics().density*value+.5f); }
    private TextView label(int text) {
        TextView v=new TextView(this); if(text!=0) v.setText(text); v.setPadding(0,dp(10),0,dp(4)); form.addView(v); return v;
    }
    private EditText field(int title,boolean number) {
        label(title); EditText v=new EditText(this); v.setSingleLine(true);
        v.setInputType(number?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(v); return v;
    }
    private CheckBox check(int title) { CheckBox v=new CheckBox(this); v.setText(title); form.addView(v); return v; }
    private void button(int title,Runnable action) {
        Button b=new Button(this); b.setText(title); b.setAllCaps(false); form.addView(b);
        b.setOnClickListener(v->{ Libre3EmulatorLog.i("UI","action="+getString(title)+" host="+selectedNode()); note=""; safe(action); });
    }
    private void safe(Runnable action) { try { action.run(); } catch(Exception e) {
        Libre3EmulatorLog.error("UI","action failed",e); error(e.getMessage()==null?e.toString():e.getMessage());
    } }
    private void error(String message) {
        note=message; Libre3EmulatorLog.w("UI",message);
        if(!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok,null).show();
    }
    private void toast(int message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    private String text(EditText field) { return field.getText().toString().trim(); }
    private int number(EditText field) { return Integer.parseInt(text(field)); }
    private String selectedNode() {
        int i=host==null?-1:host.getSelectedItemPosition(); return i>=0 && i<nodeIds.size()?nodeIds.get(i):config.targetNode;
    }
    private Libre3EmulatorConfig read() {
        Libre3EmulatorConfig c=config.copy();
        c.name=text(name); c.serial=text(serial).toUpperCase(Locale.ROOT); c.targetNode=selectedNode();
        String p=text(pin).replaceFirst("^(0x|0X)","");
        if(!p.matches("[0-9a-fA-F]{1,8}")) throw new IllegalArgumentException("PIN must contain up to eight hexadecimal digits");
        c.pin=Long.parseLong(p,16); c.startTime=Long.parseLong(text(start)); c.unused=unused.isChecked();
        c.warmupMinutes=number(warmup); c.wearMinutes=number(wear); c.patchState=number(state);
        c.glucoseMode=source.getSelectedItemPosition(); c.glucose=number(glucose); c.amplitude=number(amplitude);
        c.periodMinutes=number(period); c.intervalSeconds=number(interval); c.address=text(address).toUpperCase(Locale.ROOT);
        c.certificate=Libre3EmulatorConfig.unhex(text(certificate)); c.validate();
        Libre3Emulator.prefs(this).edit().putBoolean("manual:"+c.name,manual.isChecked()).apply(); return c;
    }
    private void fill() {
        name.setText(config.name); serial.setText(config.serial); pin.setText(String.format(Locale.ROOT,"%08X",config.pin));
        start.setText(Long.toString(config.startTime)); unused.setChecked(config.unused);
        warmup.setText(Integer.toString(config.warmupMinutes)); wear.setText(Integer.toString(config.wearMinutes));
        state.setText(Integer.toString(config.patchState)); source.setSelection(config.glucoseMode);
        glucose.setText(Integer.toString(config.glucose)); amplitude.setText(Integer.toString(config.amplitude));
        period.setText(Integer.toString(config.periodMinutes)); interval.setText(Integer.toString(config.intervalSeconds));
        address.setText(config.address); certificate.setText(Libre3EmulatorConfig.hex(config.certificate));
        manual.setChecked(Libre3Emulator.prefs(this).getBoolean("manual:"+config.name,false));
        setNodes(config.targetNode,Collections.emptyList());
    }
    private void chooseProfile() {
        List<String> names=Libre3Emulator.profiles(this);
        if(names.isEmpty()) { toast(R.string.l3emu_no_profiles); return; }
        new AlertDialog.Builder(this).setTitle(R.string.l3emu_load).setItems(names.toArray(new String[0]),(d,i)->safe(()->{
            config=Libre3Emulator.load(this,names.get(i)); Libre3Emulator.save(this,config); fill(); refreshNodes();
        })).show();
    }
    private void setNodes(String selected,List<com.google.android.gms.wearable.Node> nodes) {
        nodeIds.clear(); nodeNames.clear(); nodeIds.add(""); nodeNames.add(getString(R.string.l3emu_local));
        for(com.google.android.gms.wearable.Node n:nodes) { nodeIds.add(n.getId()); nodeNames.add(n.getDisplayName()); }
        nodeIds.add("external"); nodeNames.add(getString(R.string.l3emu_external));
        if(!nodeIds.contains(selected)) { nodeIds.add(selected); nodeNames.add("Watch: "+selected); }
        host.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,nodeNames));
        host.setSelection(nodeIds.indexOf(selected));
    }
    private void refreshNodes() {
        String selected=selectedNode();
        Wearable.getNodeClient(this).getConnectedNodes().addOnSuccessListener(nodes->{
            Libre3EmulatorLog.i("UI","refresh connected Wear nodes="+nodes.size());
            if(isFinishing() || isDestroyed()) return; setNodes(selected,nodes);
            if(!selected.isEmpty() && !selected.equals("external")) Libre3EmulatorWear.send(this,selected,"query",new byte[0]);
        }).addOnFailureListener(e->{
            Libre3EmulatorLog.error("UI","refresh Wear nodes failed",e);
            if(!isFinishing()) note="Wear connection: "+e.getMessage();
        });
    }
    private void permissions(Runnable action) {
        List<String> wanted=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31) Collections.addAll(wanted,Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE);
        else wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(Build.VERSION.SDK_INT>=33) wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        wanted.removeIf(p->checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED);
        Libre3EmulatorLog.i("UI","missing permissions="+wanted);
        if(wanted.isEmpty()) { action.run(); return; }
        permissionAction=action; requestPermissions(wanted.toArray(new String[0]),3);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=3) return; Runnable action=permissionAction; permissionAction=null;
        if(results.length==0 || results.length!=permissions.length) return;
        for(int i=0;i<permissions.length;i++) Libre3EmulatorLog.i("UI","permission="+permissions[i]+" granted="+(results[i]==PackageManager.PERMISSION_GRANTED));
        for(int i=0;i<permissions.length;i++) if(!permissions[i].equals(Manifest.permission.POST_NOTIFICATIONS) &&
            results[i]!=PackageManager.PERMISSION_GRANTED) { error(getString(R.string.l3emu_permissions)); return; }
        if(action!=null) safe(action);
    }
    private void find() {
        if(scanner!=null) scanner.close();
        if(reporter!=null) { reporter.close(); reporter=null; }
        helperAddress=""; note=getString(R.string.l3emu_scanning);
        if(Libre3Emulator.running!=null) note+=" Use a second device to find this device's advertising address.";
        Libre3EmulatorLog.i("UI","standalone helper scan localEmulatorRunning="+(Libre3Emulator.running!=null));
        scanner=new Libre3EmulatorAddress(this,null,new Libre3EmulatorAddress.Listener() {
            public void found(String found,byte[] token) { main.post(()->{
                if(isFinishing() || isDestroyed()) return;
                helperAddress=found; note="Other emulator found: "+found; Libre3EmulatorLog.i("UI",note);
                new AlertDialog.Builder(Libre3EmulatorActivity.this).setTitle(R.string.l3emu_other_found)
                    .setMessage(getString(R.string.l3emu_other_help,found))
                    .setPositiveButton(R.string.l3emu_report,(d,w)->{
                        if(reporter!=null) reporter.close();
                        note="Reporting "+found+" to the other emulator";
                        reporter=Libre3EmulatorAddress.report(Libre3EmulatorActivity.this,found,token,
                        new Libre3EmulatorAddress.Listener() {
                            public void found(String a,byte[] t) { main.post(()->{
                                if(isFinishing() || isDestroyed()) return;
                                // This is the OTHER emulator's address. It must
                                // not overwrite the selected host's NFC field.
                                note="Address reported to the other emulator: "+a;
                            }); }
                            public void error(String message) { main.post(()->Libre3EmulatorActivity.this.error(message)); }
                        });
                    }).setNeutralButton(R.string.l3emu_copy_other,(d,w)->{
                        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("Other emulator address",found));
                        note="Copied the other emulator address: "+found;
                    }).setNegativeButton(android.R.string.cancel,null).show();
            }); }
            public void error(String message) { main.post(()->Libre3EmulatorActivity.this.error(message)); }
        }); scanner.start();
    }
    @Override protected void onResume() { super.onResume(); if(config!=null) main.post(update); }
    @Override protected void onPause() { main.removeCallbacks(update); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        if(config!=null && name!=null) try { out.putByteArray("config",read().encode()); } catch(Exception ignored) { }
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        if(scanner!=null) scanner.close(); if(reporter!=null) reporter.close();
        main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
