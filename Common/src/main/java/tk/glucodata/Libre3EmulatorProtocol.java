/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;

/** Sensor state machine. All calls belong to the GATT server's single worker. */
final class Libre3EmulatorProtocol implements AutoCloseable {
    static UUID uuid(String first) { return UUID.fromString(first+"-ef89-11e9-81b4-2a2ae2dbcce4"); }
    static final UUID DATA=uuid("089810cc"), SECURITY=uuid("0898203a");
    static final UUID CONTROL=uuid("08981338"), STATUS=uuid("08981482"), GLUCOSE=uuid("0898177a"),
        HISTORY=uuid("0898195a"), CLINICAL=uuid("08981ab8"), EVENTS=uuid("08981bee"), FACTORY=uuid("08981d24"),
        COMMAND=uuid("08982198"), CHALLENGE=uuid("089822ce"), CERT=uuid("089823fa");
    // The public key in the known security-version-1 application certificate.
    private static final byte[] APP_PUBLIC=Libre3EmulatorConfig.unhex(
        "048242BE33F1A330880112FA62CC4842A43D1204922AD201D8775BB226F611F75B0EF3D5BC6CC4317CAA457584AB003F1712336089D3A4F29838ED0DC666DEAEA2");
    interface Store {
        byte[] authorization();
        void authorization(byte[] record);
        long activation();
        void activation(long seconds);
        Reading sample(int minute);
        void sample(int minute,Reading reading);
    }
    interface Source {
        Reading latest();
        default Reading[] history(long from,long through) { return new Reading[0]; }
    }
    interface Diagnostic {
        void message(String message);
        default void state(String message) { message(message); }
    }
    static String label(UUID id) {
        if(id.equals(COMMAND)) return "COMMAND";
        if(id.equals(CERT)) return "CERT";
        if(id.equals(CHALLENGE)) return "CHALLENGE";
        if(id.equals(CONTROL)) return "CONTROL";
        if(id.equals(STATUS)) return "STATUS";
        if(id.equals(GLUCOSE)) return "GLUCOSE";
        if(id.equals(HISTORY)) return "HISTORY";
        if(id.equals(CLINICAL)) return "CLINICAL";
        if(id.equals(EVENTS)) return "EVENTS";
        if(id.equals(FACTORY)) return "FACTORY";
        return id.toString();
    }
    static final class Reading {
        final long time; final int glucose, rate;
        Reading(long time,int glucose,int rate) { this.time=time; this.glucose=glucose; this.rate=rate; }
    }
    static final class Packet {
        final UUID characteristic; final byte[] value;
        Packet(UUID c,byte[] v) { characteristic=c; value=v; }
    }
    private enum Phase { IDLE, APP_CERT, APP_ACCEPTED, EPHEMERAL, ROOT, CHALLENGE, AUTHORIZED }
    private final Libre3EmulatorConfig config;
    private final Libre3EmulatorCrypto crypto;
    private final Store store;
    private final Source source;
    private final Diagnostic diagnostic;
    private final LongSupplier clock;
    private final SecureRandom random;
    private final Set<UUID> subscribed=new HashSet<>();
    private final ArrayDeque<Packet> outgoing=new ArrayDeque<>();
    private final ArrayDeque<byte[]> controls=new ArrayDeque<>();
    private Phase phase=Phase.IDLE;
    private byte[] incoming; private int received;
    private byte[] ownPublic,r1,nonce;
    private int lastMinute=-1,lastControlCounter=-1;
    private long lastSourceTime;
    private final Map<Integer,Reading> imported=new HashMap<>();
    private int lastSecurityCommand=-1;
    private long activation;
    private Backfill backfill;

    Libre3EmulatorProtocol(Libre3EmulatorConfig config,Libre3EmulatorCrypto crypto,Store store,
            Source source,Diagnostic diagnostic,LongSupplier clock,SecureRandom random) {
        this.config=config.copy(); this.crypto=crypto; this.store=store; this.source=source;
        this.diagnostic=diagnostic; this.clock=clock; this.random=random;
        activation=store.activation();
        if(activation==0 && !config.unused) {
            activation=config.startTime==0?clock.getAsLong():config.startTime;
            store.activation(activation);
        }
        diagnostic.message("Protocol initialized "+snapshot()+" activation="+activation);
    }
    String snapshot() {
        return "phase="+phase+" lastCommand="+lastSecurityCommand+" received="+received+"/"+(incoming==null?0:incoming.length)+
            " queued="+outgoing.size()+" controls="+controls.size()+" subscriptions="+subscribed.size();
    }
    boolean authorized() { return phase==Phase.AUTHORIZED; }
    long activation() { return activation; }
    void activate(long requestedTime) {
        if(activation!=0) return;
        activation=config.startTime!=0?config.startTime:(requestedTime>0?requestedTime:clock.getAsLong());
        store.activation(activation); diagnostic.state("Sensor activated at "+activation);
    }
    void subscribe(UUID characteristic,boolean enable) {
        if(enable) subscribed.add(characteristic); else subscribed.remove(characteristic);
        diagnostic.message("Subscribe "+label(characteristic)+" enabled="+enable+" phase="+phase);
        if(enable && authorized()) {
            if(characteristic.equals(GLUCOSE)) {
                int end=dataLife(); importHistory(Math.max(0,end-25),end);
                minute(true);
            }
            if(characteristic.equals(STATUS)) status();
        }
    }
    void write(UUID characteristic,byte[] value) {
        diagnostic.message("Write "+label(characteristic)+" bytes="+(value==null?0:value.length)+" phase="+phase);
        if(value==null || value.length==0 || value.length>512) fail("Invalid write length");
        if(characteristic.equals(COMMAND)) {
            if(value.length!=1) fail("Invalid security command length");
            security(value[0]&255);
        } else if(characteristic.equals(CERT)) {
            if(phase!=Phase.APP_CERT && phase!=Phase.EPHEMERAL) fail("Unexpected certificate data");
            fragment(value);
        } else if(characteristic.equals(CHALLENGE)) {
            if(phase!=Phase.CHALLENGE) fail("Unexpected challenge data");
            fragment(value);
        } else if(characteristic.equals(CONTROL)) {
            if(!authorized()) fail("Data control before authorization");
            if(value.length<7) fail("Short encrypted control");
            int counter=u16(value,value.length-2);
            if(counter<=lastControlCounter) fail("Repeated data-control counter="+counter+" previous="+lastControlCounter);
            byte[] plain=crypto.decrypt(0,value);
            if(plain==null || plain.length!=7) fail("Invalid encrypted control");
            lastControlCounter=counter;
            if(controls.size()>=16) fail("Too many pending controls");
            controls.add(plain);
            diagnostic.message("Encrypted control accepted counter="+counter+" op="+(plain[0]&255));
        } else fail("Unsupported characteristic write");
    }
    private void security(int command) {
        lastSecurityCommand=command;
        diagnostic.state("Security command "+command+" phase="+phase);
        switch(command) {
            case 1:
                phase=Phase.APP_CERT; outgoing.clear(); controls.clear(); backfill=null;
                crypto.fresh(config.certificate); ownPublic=null; incoming=null; received=0;
                break;
            case 2:
                require(Phase.APP_CERT); receive(162); break;
            case 3:
                require(Phase.APP_CERT); complete(162);
                if(!Arrays.equals(APP_PUBLIC,Arrays.copyOfRange(incoming,33,98)))
                    fail("Unknown application public key; this emulator supports security version 1");
                phase=Phase.APP_ACCEPTED; queue(COMMAND,new byte[]{4}); break;
            case 9:
                require(Phase.APP_ACCEPTED); transfer(10,CERT,config.certificate); break;
            case 13:
                require(Phase.APP_ACCEPTED); ownPublic=crypto.publicKey(); receive(65); phase=Phase.EPHEMERAL; break;
            case 14:
                require(Phase.EPHEMERAL); complete(65); crypto.derive(incoming);
                phase=Phase.ROOT; transfer(15,CERT,ownPublic); break;
            case 17:
                if(phase==Phase.IDLE) {
                    byte[] saved=store.authorization();
                    diagnostic.message("Resume authorization bytes="+(saved==null?0:saved.length));
                    if(saved==null || saved.length!=149) fail("No saved authorization; start a fresh pairing");
                    crypto.resume(saved); phase=Phase.ROOT;
                }
                require(Phase.ROOT);
                r1=random(16); nonce=random(7);
                byte[] first=new byte[23]; System.arraycopy(r1,0,first,0,16); System.arraycopy(nonce,0,first,16,7);
                transfer(8,CHALLENGE,first); receive(40); phase=Phase.CHALLENGE; break;
            case 8:
                require(Phase.CHALLENGE); complete(40); finishChallenge(); break;
            default: fail("Unsupported security command "+command);
        }
        diagnostic.message("Security command completed "+snapshot());
    }
    private void finishChallenge() {
        byte[] plain=crypto.decryptReply(nonce,incoming);
        if(plain==null || plain.length!=36) fail("Challenge decryption/tag failed; check fresh versus saved authorization");
        if(!Arrays.equals(r1,Arrays.copyOfRange(plain,0,16))) { Arrays.fill(plain,(byte)0); fail("Challenge r1 mismatch"); }
        if(u32(plain,32)!=config.pin) { Arrays.fill(plain,(byte)0); fail("Challenge PIN mismatch; NFC and Bluetooth profiles must agree"); }
        byte[] key=random(16),iv=random(8),reply=new byte[56];
        System.arraycopy(plain,16,reply,0,16); System.arraycopy(r1,0,reply,16,16);
        System.arraycopy(key,0,reply,32,16); System.arraycopy(iv,0,reply,48,8);
        byte[] finalNonce=random(7),cipher=crypto.encryptResponse(finalNonce,reply);
        if(cipher==null || cipher.length!=60) fail("Cannot encrypt authorization response");
        byte[] result=new byte[67]; System.arraycopy(cipher,0,result,0,60); System.arraycopy(finalNonce,0,result,60,7);
        crypto.dataKey(key,iv); Arrays.fill(key,(byte)0); Arrays.fill(reply,(byte)0); Arrays.fill(plain,(byte)0);
        byte[] saved=crypto.saved();
        if(saved==null || saved.length!=149) fail("Cannot save authorization");
        store.authorization(saved); transfer(8,CHALLENGE,result);
        phase=Phase.AUTHORIZED; lastControlCounter=-1; diagnostic.state("Session authorized");
    }
    private byte[] random(int n) { byte[] b=new byte[n]; random.nextBytes(b); return b; }
    private void require(Phase expected) { if(phase!=expected) fail("Expected security phase "+expected); }
    private void fail(String message) {
        String detail=message+"; "+snapshot(); diagnostic.message("REJECT "+detail);
        throw new IllegalArgumentException(detail);
    }
    private void receive(int n) { incoming=new byte[n]; received=0; diagnostic.message("Expect security data bytes="+n); }
    private void complete(int n) { if(incoming==null || incoming.length!=n || received!=n) fail("Incomplete security data expected="+n); }
    private void fragment(byte[] value) {
        diagnostic.message("Fragment offset="+(value.length<2?-1:u16(value,0))+" bytes="+value.length+" expectedOffset="+received+
            " total="+(incoming==null?0:incoming.length));
        if(incoming==null || value.length<3 || value.length>20 || u16(value,0)!=received) fail("Invalid security fragment offset");
        int count=Math.min(value.length-2,incoming.length-received);
        if(count<=0) fail("Excess security data");
        for(int i=2+count;i<value.length;i++) if(value[i]!=0) fail("Nonzero security padding");
        System.arraycopy(value,2,incoming,received,count); received+=count;
    }
    private void transfer(int signal,UUID c,byte[] data) {
        diagnostic.message("Transfer "+label(c)+" signal="+signal+" bytes="+data.length+" subscribed="+subscribed.contains(c));
        queue(COMMAND,new byte[]{(byte)signal,(byte)data.length});
        for(int offset=0,sequence=0;offset<data.length;offset+=19,sequence++) {
            int n=Math.min(19,data.length-offset); byte[] b=new byte[n+1]; b[0]=(byte)sequence;
            System.arraycopy(data,offset,b,1,n); queue(c,b);
        }
    }
    private void queue(UUID c,byte[] value) {
        if(outgoing.size()>=128) fail("Notification queue exhausted");
        outgoing.add(new Packet(c,value));
    }
    private void encrypted(UUID c,int kind,byte[] plain) {
        if(!subscribed.contains(c)) return;
        byte[] encrypted=crypto.encrypt(kind,plain);
        // Glucose is 35 bytes and is always sent as 20+15, also with a larger MTU.
        for(int i=0;i<encrypted.length;i+=20) queue(c,Arrays.copyOfRange(encrypted,i,Math.min(i+20,encrypted.length)));
    }
    Packet poll() {
        for(int attempts=0;attempts<32;attempts++) {
            if(!outgoing.isEmpty()) {
                Packet p=outgoing.remove();
                if(subscribed.contains(p.characteristic)) return p;
                diagnostic.message("Drop notification without subscription: "+label(p.characteristic)+" bytes="+p.value.length);
                continue;
            }
            if(backfill!=null) { backfill.next(); continue; }
            if(!controls.isEmpty()) { control(controls.remove()); continue; }
            return null;
        }
        return null;
    }
    boolean pending() { return !outgoing.isEmpty() || backfill!=null || !controls.isEmpty(); }
    void tick() { if(config.glucoseMode!=2 && authorized()) { minute(false); status(); } }
    void sourceChanged(Reading actual) {
        if(config.glucoseMode!=2) return;
        live(actual,false);
        if(authorized()) status();
    }
    private int life() { return activation==0?0:(int)Math.min(32767,Math.max(0,(clock.getAsLong()-activation)/60)); }
    static int sampleMinute(long activation,Reading r) {
        if(activation==0 || r==null || r.time<activation) return -1;
        long minute=(r.time-activation)/60;
        return minute>32767?-1:(int)minute;
    }
    private int dataLife() {
        return config.glucoseMode==2?Math.max(0,Math.min(life(),sampleMinute(activation,source.latest()))):life();
    }
    private void importHistory(int from,int through) {
        if(config.glucoseMode!=2 || activation==0 || through<from) return;
        int count=0;
        for(Reading r:source.history(activation+from*60L,activation+(through+1L)*60-1)) {
            int at=sampleMinute(activation,r);
            if(at<from || at>through || r.glucose<39 || r.glucose>501) continue;
            Reading old=imported.get(at);
            if(old==null || r.time>=old.time) { imported.put(at,r); count++; }
        }
        diagnostic.message("Source history from="+from+" to="+through+" samples="+count);
    }
    private Reading reading(int minute) {
        if(activation==0 || minute<config.warmupMinutes || minute>config.wearMinutes) return new Reading(0,0,0);
        if(config.glucoseMode==2) {
            Reading stored=imported.get(minute);
            if(stored==null) stored=store.sample(minute);
            // Earlier versions stored a stale value under the timer's minute.
            return sampleMinute(activation,stored)!=minute?new Reading(0,0,0):stored;
        }
        double angle=minute*2*Math.PI/config.periodMinutes;
        int value=config.glucoseMode==1?config.glucose+(int)Math.round(config.amplitude*Math.sin(angle)):config.glucose;
        int rate=config.glucoseMode==1?(int)Math.round(100*config.amplitude*2*Math.PI/config.periodMinutes*Math.cos(angle)):0;
        rate=Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,rate));
        return new Reading(activation+minute*60L,Math.max(39,Math.min(501,value)),rate);
    }
    private void minute(boolean force) {
        if(config.glucoseMode==2) { live(source.latest(),force); return; }
        if(!subscribed.contains(GLUCOSE)) return;
        int life=life();
        if(!force && life==lastMinute) return;
        sendMinute(life,reading(life));
    }
    private void live(Reading actual,boolean force) {
        int at=sampleMinute(activation,actual); long now=clock.getAsLong();
        if(at<config.warmupMinutes || at>config.wearMinutes || actual==null || actual.glucose<39 || actual.glucose>501 ||
                now-actual.time>180 || actual.time>now+60 || actual.time<lastSourceTime) return;
        store.sample(at,actual); imported.put(at,actual);
        if(!authorized() || !subscribed.contains(GLUCOSE) || (!force && actual.time<=lastSourceTime)) return;
        sendMinute(at,actual); lastSourceTime=actual.time;
        diagnostic.message("Live sourceTime="+actual.time+" queuedAt="+now+" ageSeconds="+(now-actual.time)+" minute="+at);
    }
    private void sendMinute(int life,Reading r) {
        int historic=Math.max(0,((life-16)/5)*5); Reading h=reading(historic);
        byte[] p=new byte[29]; put16(p,0,life); put16(p,2,r.glucose); put16(p,4,r.rate);
        put16(p,8,r.glucose); put16(p,10,historic); put16(p,12,h.glucose);
        p[14]=(byte)(r.rate==Short.MIN_VALUE?0:r.rate<-200?1:r.rate<-100?2:r.rate>200?5:r.rate>100?4:3);
        put16(p,15,r.glucose); put16(p,17,h.glucose); put16(p,19,3200);
        encrypted(GLUCOSE,3,p); lastMinute=life;
        diagnostic.message("Glucose queued minute="+life+" available="+(r.time!=0));
    }
    private void status() {
        if(!subscribed.contains(STATUS)) return;
        byte[] p=new byte[12]; put16(p,0,life()); p[6]=(byte)255;
        p[7]=(byte)(activation==0?1:config.patchState); put16(p,8,dataLife()); encrypted(STATUS,2,p);
    }
    private void control(byte[] command) {
        int op=command[0]&255;
        diagnostic.message("Data control op="+op+" subtype="+(command[1]&255));
        if(op==1 && ((command[1]&255)==0 || (command[1]&255)==1)) {
            int kind=command[1]&255; long from=u32(command,3);
            if(from>65535) fail("Invalid backfill life count");
            int end=dataLife();
            importHistory(Math.max(0,(int)from-25),end);
            backfill=new Backfill(kind,(int)from,end);
            diagnostic.message("Backfill kind="+kind+" from="+from+" to="+backfill.end);
        } else if(op==4) {
            byte[] none=new byte[7]; none[6]=(byte)255; encrypted(EVENTS,6,none);
            encrypted(CONTROL,1,new byte[]{4,0,0});
        } else {
            diagnostic.state("Unsupported data control "+op);
            encrypted(CONTROL,1,new byte[]{(byte)op,0,1});
        }
    }
    private final class Backfill {
        final int kind,end; int at,available,missing;
        Backfill(int kind,int from,int end) {
            this.kind=kind;
            this.end=Math.min(kind==0?Math.max(0,((end-16)/5)*5):end,config.wearMinutes);
            // Clinical requests already contain the first missing minute.
            at=kind==0?((from+4)/5)*5:from;
        }
        int value(int minute) {
            int value=reading(minute).glucose;
            if(value>=39 && value<=501) available++; else missing++;
            return value;
        }
        void next() {
            UUID characteristic=kind==0?HISTORY:CLINICAL;
            if(at>end || !subscribed.contains(characteristic)) {
                diagnostic.message("Backfill finished kind="+kind+" next="+at+" end="+end+" available="+available+
                    " missing="+missing+" subscribed="+subscribed.contains(characteristic));
                encrypted(CONTROL,1,new byte[]{1,(byte)kind,0}); backfill=null; return;
            }
            if(kind==0) {
                int count=Math.min(6,(end-at)/5+1); byte[] p=new byte[2+count*2]; put16(p,0,at);
                for(int i=0;i<count;i++,at+=5) put16(p,2+2*i,value(at));
                encrypted(HISTORY,4,p);
            } else {
                byte[] p=new byte[14]; put16(p,0,at); put16(p,10,value(at));
                put16(p,12,reading(Math.max(0,(int)Math.round((at-19.0)/5)*5)).glucose);
                at++; encrypted(CLINICAL,5,p);
            }
        }
    }
    static int u16(byte[] b,int i) { return (b[i]&255)|((b[i+1]&255)<<8); }
    static long u32(byte[] b,int i) { return u16(b,i)|((long)u16(b,i+2)<<16); }
    static void put16(byte[] b,int i,int v) { b[i]=(byte)v; b[i+1]=(byte)(v>>>8); }
    public void close() { diagnostic.message("Protocol close "+snapshot()); crypto.close(); outgoing.clear(); controls.clear(); }
}
