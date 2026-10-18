package com.viitube.proxy;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLServerSocketFactory;

import fi.iki.elonen.NanoHTTPD;

/**
 * A self-signed certificate for {@code https://localhost}, used only by this proxy's own HTTPS
 * server on the loopback interface. The newer YouTube apps refuse plain HTTP, so on a rooted
 * device the proxy serves HTTPS and (see {@link SystemCertStore}) trusts this certificate.
 *
 * <p>The key pair is generated on the device on first run and kept in the app's private storage.
 * It is never shipped in the APK, so no two installs share a key, and the private key never
 * leaves the phone. The certificate is for CN=localhost (SAN localhost / 127.0.0.1) only.
 *
 * <p>The X.509 structure is encoded by hand (small DER writer) so the class needs no crypto
 * library beyond the JDK and runs on Android 2.3+.
 */
final class LocalCertificate {

    private static final String ALIAS = "localhost";
    private static final char[] PASSWORD = "localhost".toCharArray();

    private final X509Certificate certificate;
    private final PrivateKey privateKey;
    /** OpenSSL {@code subject_hash_old}: the Android system trust store file name is hash + ".0". */
    private final String androidStoreHash;

    private LocalCertificate(X509Certificate certificate, PrivateKey privateKey, byte[] subjectDer) {
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.androidStoreHash = opensslOldHash(subjectDer);
    }

    X509Certificate certificate() {
        return certificate;
    }

    /** File name this certificate must have under {@code /system/etc/security/cacerts/}. */
    String androidStoreFileName() {
        return androidStoreHash + ".0";
    }

    /** PEM form written to the system trust store. */
    String toPem() {
        try {
            String base64 = android.util.Base64.encodeToString(
                    certificate.getEncoded(), android.util.Base64.NO_WRAP);
            StringBuilder pem = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
            for (int i = 0; i < base64.length(); i += 64) {
                pem.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
            }
            return pem.append("-----END CERTIFICATE-----\n").toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode certificate", e);
        }
    }

    /** TLS server socket factory for NanoHTTPD, backed by this certificate and its key. */
    SSLServerSocketFactory serverSocketFactory() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry(ALIAS, privateKey, PASSWORD, new java.security.cert.Certificate[]{certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, PASSWORD);
        return NanoHTTPD.makeSSLSocketFactory(keyStore, keyManagers);
    }

    /** Loads the stored certificate, or generates and stores a new one on first run. */
    static synchronized LocalCertificate loadOrCreate(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "https");
        File keyFile = new File(directory, "localhost.pk8");
        File certFile = new File(directory, "localhost.crt");
        if (keyFile.exists() && certFile.exists()) {
            try {
                PrivateKey key = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(readFile(keyFile)));
                byte[] certDer = readFile(certFile);
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(certDer));
                return new LocalCertificate(cert, key, subjectDer());
            } catch (Exception e) {
                android.util.Log.w("LocalCertificate", "Stored certificate unusable; regenerating", e);
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        byte[] certDer = buildSelfSigned(pair);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        directory.mkdirs();
        writeFile(keyFile, pair.getPrivate().getEncoded());
        writeFile(certFile, certDer);
        return new LocalCertificate(cert, pair.getPrivate(), subjectDer());
    }

    // ------------------------------------------------------------------ X.509 / DER

    // Fixed OID encodings (tag + length + contents), to avoid an OID encoder.
    private static final byte[] OID_SHA256_RSA =
            {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] OID_COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};
    private static final byte[] OID_BASIC_CONSTRAINTS = {0x06, 0x03, 0x55, 0x1D, 0x13};
    private static final byte[] OID_KEY_USAGE = {0x06, 0x03, 0x55, 0x1D, 0x0F};
    private static final byte[] OID_SUBJECT_ALT_NAME = {0x06, 0x03, 0x55, 0x1D, 0x11};

    private static byte[] subjectDer() {
        // Name ::= SEQUENCE { RDN SET { ATV SEQUENCE { CN, UTF8String "localhost" } } }
        byte[] atv = seq(concat(OID_COMMON_NAME, tlv(0x0C, bytes("localhost"))));
        return seq(tlv(0x31, atv));
    }

    private static byte[] buildSelfSigned(KeyPair pair) throws Exception {
        byte[] name = subjectDer();
        byte[] sigAlg = seq(concat(OID_SHA256_RSA, new byte[]{0x05, 0x00}));

        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7F;
        if (serial[0] == 0) serial[0] = 0x01;

        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] validity = seq(concat(
                tlv(0x17, bytes(utc.format(new Date(now - 24L * 60 * 60 * 1000)))),
                tlv(0x17, bytes(utc.format(new Date(now + 3650L * 24 * 60 * 60 * 1000))))));

        byte[] subjectPublicKeyInfo = pair.getPublic().getEncoded(); // already a DER SPKI

        // basicConstraints cA:TRUE (critical) so this is accepted as a trust anchor.
        byte[] basicConstraints = seq(concat(OID_BASIC_CONSTRAINTS, bool(),
                tlv(0x04, seq(bool()))));
        // keyUsage: digitalSignature, keyEncipherment, keyCertSign.
        byte[] keyUsage = seq(concat(OID_KEY_USAGE, bool(),
                tlv(0x04, new byte[]{0x03, 0x02, 0x02, (byte) 0xA4})));
        // subjectAltName: dNSName "localhost", iPAddress 127.0.0.1.
        byte[] altNames = seq(concat(
                tlv(0x82, bytes("localhost")),
                tlv(0x87, new byte[]{127, 0, 0, 1})));
        byte[] subjectAltName = seq(concat(OID_SUBJECT_ALT_NAME, tlv(0x04, altNames)));
        byte[] extensions = tlv(0xA3, seq(concat(basicConstraints, keyUsage, subjectAltName)));

        byte[] tbs = seq(concat(
                tlv(0xA0, new byte[]{0x02, 0x01, 0x02}), // version v3
                tlv(0x02, serial),
                sigAlg,
                name,
                validity,
                name,
                subjectPublicKeyInfo,
                extensions));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();

        return seq(concat(tbs, sigAlg, tlv(0x03, concat(new byte[]{0x00}, signature))));
    }

    private static String opensslOldHash(byte[] nameDer) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(nameDer);
            long value = (digest[0] & 0xffL) | (digest[1] & 0xffL) << 8
                    | (digest[2] & 0xffL) << 16 | (digest[3] & 0xffL) << 24;
            return String.format(Locale.US, "%08x", value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] seq(byte[] content) {
        return tlv(0x30, content);
    }

    private static byte[] bool() {
        return new byte[]{0x01, 0x01, (byte) 0xFF};
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 128) {
            out.write(length);
        } else {
            byte[] encoded = new byte[4];
            int count = 0;
            for (int value = length; value != 0; value >>>= 8) {
                encoded[count++] = (byte) value;
            }
            out.write(0x80 | count);
            for (int i = count - 1; i >= 0; i--) out.write(encoded[i] & 0xff);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] bytes(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readFile(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        FileInputStream in = new FileInputStream(file);
        try {
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
        } finally {
            in.close();
        }
        return data;
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
