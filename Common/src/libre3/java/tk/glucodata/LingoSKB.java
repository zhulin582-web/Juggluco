/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>
 */
package tk.glucodata;

import android.content.Context;

/** Error-handling adapter for the independent Lingo implementation. */
final class LingoSKB {
    private static final String LOG_ID = "LingoSKB";
    private final LingoCrypto crypto;

    private LingoSKB() throws java.security.GeneralSecurityException {
        crypto = new LingoCrypto();
    }

    static LingoSKB create(Context context, String serial) {
        try {
            return new LingoSKB();
        } catch (Exception e) {
            err("create", e);
            return null;
        }
    }

    static boolean canResume(byte[] savedAuthorization, int securityVersion) {
        return LingoCrypto.canResume(savedAuthorization, securityVersion);
    }

    int getKeyIndexFromVersion(int securityVersion) {
        return LingoCrypto.getKeyIndexFromVersion(securityVersion);
    }

    synchronized boolean initECDH(byte[] savedAuthorization, int securityVersion) {
        try {
            crypto.initECDH(savedAuthorization, securityVersion);
            return true;
        } catch (Exception e) { err("initECDH", e); return false; }
    }

    synchronized byte[] getAppCertificate() {
        try { return crypto.getAppCertificate(); }
        catch (Exception e) { err("getAppCertificate", e); return null; }
    }

    synchronized boolean setPatchCertificate(byte[] certificate) {
        try {
            crypto.setPatchCertificate(certificate);
            return true;
        } catch (Exception e) { err("setPatchCertificate", e); return false; }
    }

    synchronized byte[] generateEphemeralKeys() {
        try { return crypto.generateEphemeralKeys(); }
        catch (Exception e) { err("generateEphemeralKeys", e); return null; }
    }

    synchronized boolean generateKAuth(byte[] peer) {
        try {
            crypto.generateKAuth(peer);
            return true;
        } catch (Exception e) { err("generateKAuth", e); return false; }
    }

    synchronized byte[] encrypt(byte[] nonce, byte[] plain) {
        try { return crypto.encrypt(nonce, plain); }
        catch (Exception e) { err("encrypt", e); return null; }
    }

    synchronized byte[] decrypt(byte[] nonce, byte[] ciphertext) {
        try { return crypto.decrypt(nonce, ciphertext); }
        catch (Exception e) { err("decrypt", e); return null; }
    }

    synchronized byte[] exportAuthorizationKey() {
        try { return crypto.exportAuthorizationKey(); }
        catch (Exception e) { err("exportAuthorizationKey", e); return null; }
    }

    private static void err(String operation, Exception e) {
        Log.e(LOG_ID, operation + " failed: " + e);
    }
}
