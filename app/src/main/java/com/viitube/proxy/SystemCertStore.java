package com.viitube.proxy;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Adds the proxy's {@link LocalCertificate} to the Android system trust store so the local HTTPS
 * server is trusted, and removes it again when the proxy stops. This needs root and only ever
 * touches one file, {@code /system/etc/security/cacerts/<hash>.0}, named after the proxy's own
 * certificate. Without root nothing is attempted and nothing changes on the device.
 *
 * <p>Both install and removal are logged (tag {@code SystemCertStore}); the removal on stop is
 * deliberate so the proxy does not leave a trust anchor on the phone while it is not running.
 */
final class SystemCertStore {

    private static final String TAG = "SystemCertStore";
    private static final String CACERTS_DIR = "/system/etc/security/cacerts/";

    private SystemCertStore() { }

    static boolean rootAvailable() {
        return run("id", "check root") == 0;
    }

    /**
     * Writes the certificate to the system trust store. Returns true when it is in place.
     * The caller should only do this on a rooted device (see {@link #rootAvailable()}).
     */
    static boolean install(Context context, LocalCertificate certificate) {
        String target = CACERTS_DIR + certificate.androidStoreFileName();
        File staged = new File(context.getCacheDir(), "cacert.pem");
        try {
            FileOutputStream out = new FileOutputStream(staged);
            try {
                out.write(certificate.toPem().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "Could not stage certificate for install", e);
            return false;
        }
        String script = remountReadWrite()
                + "cp '" + staged.getAbsolutePath() + "' '" + target + "' && "
                + "chmod 644 '" + target + "' && "
                + "chown 0:0 '" + target + "' ; "
                + "chcon u:object_r:system_file:s0 '" + target + "' 2>/dev/null ; "
                + remountReadOnly();
        int exit = run(script, "install certificate");
        staged.delete();
        if (exit == 0) {
            android.util.Log.i(TAG, "Installed HTTPS certificate at " + target
                    + " (removed again when the proxy stops). Start the proxy before opening"
                    + " YouTube so the app picks up the certificate.");
            return true;
        }
        android.util.Log.i(TAG, "Could not install HTTPS certificate (su exit " + exit
                + "); HTTPS will not be trusted on this device.");
        return false;
    }

    /** Removes the certificate from the system trust store. Safe to call even if not installed. */
    static void remove(LocalCertificate certificate) {
        String target = CACERTS_DIR + certificate.androidStoreFileName();
        String script = remountReadWrite() + "rm -f '" + target + "' ; " + remountReadOnly();
        int exit = run(script, "remove certificate");
        if (exit == 0) {
            android.util.Log.i(TAG, "Removed HTTPS certificate " + target);
        } else {
            android.util.Log.w(TAG, "Could not remove HTTPS certificate " + target
                    + " (su exit " + exit + "); remove it manually if needed.");
        }
    }

    private static String remountReadWrite() {
        return "mount -o rw,remount /system 2>/dev/null || mount -o rw,remount / 2>/dev/null ; ";
    }

    private static String remountReadOnly() {
        return "mount -o ro,remount /system 2>/dev/null || mount -o ro,remount / 2>/dev/null || true";
    }

    private static int run(String command, String operation) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return process.waitFor();
        } catch (java.io.IOException e) {
            android.util.Log.i(TAG, "Could not " + operation + " (root access unavailable)");
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (SecurityException e) {
            android.util.Log.i(TAG, "Could not " + operation + " (command execution denied)");
            return -1;
        } finally {
            if (process != null) process.destroy();
        }
    }
}
