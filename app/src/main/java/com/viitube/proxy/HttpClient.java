package com.viitube.proxy;

import org.conscrypt.Conscrypt;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Security;
import java.util.Map;

/**
 * Lekki klient HTTP (HttpURLConnection zamiast OkHttp) + inicjalizacja TLS 1.2 przez Conscrypt
 * (potrzebne, bo domyślny SSLSocketFactory na API 16 nie negocjuje TLS 1.2 poprawnie z youtube.com).
 * Do wywołania raz, przy starcie usługi.
 */
public class HttpClient {

    public static void initTls() {
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2", "Conscrypt");
            sslContext.init(null, null, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String postJson(String urlStr, String jsonBody, String userAgent) throws IOException {
        return postJson(urlStr, jsonBody, userAgent, null);
    }

    public static String postJson(String urlStr, String jsonBody, String userAgent,
                                  Map<String, String> headers) throws IOException {
        return postJson(urlStr, jsonBody, userAgent, headers, 10000, 15000);
    }

    public static String postJson(String urlStr, String jsonBody, String userAgent,
                                  Map<String, String> headers, int connectTimeout, int readTimeout)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", userAgent);
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    conn.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(jsonBody.getBytes("UTF-8"));
            } finally {
                os.close();
            }

            int status = conn.getResponseCode();
            InputStream responseStream = status >= HttpURLConnection.HTTP_OK
                    && status < HttpURLConnection.HTTP_MULT_CHOICE
                    ? conn.getInputStream() : conn.getErrorStream();
            String response = responseStream == null ? "" : readStream(responseStream);
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                String details = response.length() > 500 ? response.substring(0, 500) : response;
                throw new IOException("HTTP " + status + " from " + urlStr
                        + (details.isEmpty() ? "" : ": " + details));
            }
            return response;
        } finally {
            conn.disconnect();
        }
    }

    public static String getUrl(String urlStr, String userAgent) throws IOException {
        return getUrl(urlStr, userAgent, 10000, 15000);
    }

    public static String getUrl(String urlStr, String userAgent,
                                int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    // --- prosty cache na dysku, odpowiednik is_cache_valid()/mtime z Pythona ---

    public static File cacheFile(File cacheDir, String subdir, String key) {
        File dir = new File(cacheDir, subdir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safe = key.replaceAll("[^a-zA-Z0-9_-]", "_");
        return new File(dir, safe + ".json");
    }

    public static boolean isFresh(File f, long maxAgeMs) {
        return f.exists() && (System.currentTimeMillis() - f.lastModified() < maxAgeMs);
    }

    public static String readFile(File f) throws IOException {
        return readStream(new FileInputStream(f));
    }

    public static void writeFile(File f, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }
}
