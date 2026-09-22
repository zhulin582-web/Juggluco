/* SPDX-License-Identifier: GPL-3.0-or-later */
package tk.glucodata;

/** Injectable boundary; production uses the same native primitives as Juggluco. */
interface Libre3EmulatorCrypto extends AutoCloseable {
    void fresh(byte[] certificate);
    void resume(byte[] authorization);
    byte[] publicKey();
    void derive(byte[] appPublic);
    byte[] saved();
    byte[] decryptReply(byte[] nonce, byte[] ciphertext);
    byte[] encryptResponse(byte[] nonce, byte[] plaintext);
    void dataKey(byte[] key, byte[] iv);
    byte[] encrypt(int kind, byte[] plaintext);
    byte[] decrypt(int kind, byte[] ciphertext);
    void close();

    final class Native implements Libre3EmulatorCrypto {
        private long security, data;
        private int sent;
        private void begin(byte[] saved) {
            security=Natives.libre3BeginSecurityHandshake(security);
            if(security==0 || Natives.libre3SelectAppKeyAndSavedAuthorization(security,1,saved)!=1)
                throw new IllegalStateException("Cannot initialize Libre 3 authorization");
        }
        public void fresh(byte[] certificate) {
            begin(null);
            if(Natives.libre3AcceptPatchCertificate(security,certificate)!=1)
                throw new IllegalArgumentException("Cannot load sensor certificate");
        }
        public void resume(byte[] saved) { begin(saved); }
        public byte[] publicKey() {
            byte[] raw=Natives.libre3CreateEphemeralPublicKey(security);
            if(raw==null || raw.length!=64) throw new IllegalStateException("Cannot generate ephemeral key");
            byte[] out=new byte[65]; out[0]=4; System.arraycopy(raw,0,out,1,64); return out;
        }
        public void derive(byte[] peer) {
            if(peer.length!=65 || peer[0]!=4 || Natives.libre3DeriveAuthorizationRoot(security,peer)!=1)
                throw new IllegalArgumentException("Invalid client ephemeral key");
        }
        public byte[] saved() { return Natives.libre3ExportSavedAuthorization(security); }
        public byte[] decryptReply(byte[] nonce,byte[] cipher) { return Natives.libre3EmulatorDecryptReply(security,nonce,cipher); }
        public byte[] encryptResponse(byte[] nonce,byte[] plain) { return Natives.libre3EmulatorEncryptResponse(security,nonce,plain); }
        public void dataKey(byte[] key,byte[] iv) { data=Natives.initcrypt(data,key,iv); sent=0; }
        public byte[] encrypt(int kind,byte[] plain) {
            // A new data key is required before a 16-bit packet counter repeats.
            if(data==0 || kind<1 || kind>7 || ++sent>65535) throw new IllegalStateException("Reconnect to renew the data key");
            byte[] out=Natives.intEncrypt(data,kind,plain);
            if(out==null) throw new IllegalStateException("Cannot encrypt sensor packet");
            return out;
        }
        public byte[] decrypt(int kind,byte[] cipher) {
            return data==0 || kind!=0 || cipher.length<7 ? null : Natives.intDecrypt(data,kind,cipher);
        }
        public void close() {
            if(data!=0) { Natives.endcrypt(data); data=0; }
            if(security!=0) { Natives.libre3FreeSecurityContext(security); security=0; }
        }
    }
}
