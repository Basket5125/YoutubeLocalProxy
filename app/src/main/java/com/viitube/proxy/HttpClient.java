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
        // Reuse TLS connections: a new Conscrypt handshake costs hundreds of ms on old phones.
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "16");
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
        CrashReporter.logHttpRequest("POST", urlStr, jsonBody);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
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
            String response = responseStream == null ? "" : readStream(decode(conn, responseStream));
            CrashReporter.logHttpResponse("POST", urlStr, status, response);
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                String details = response.length() > 500 ? response.substring(0, 500) : response;
                throw new IOException("HTTP " + status + " from " + urlStr
                        + (details.isEmpty() ? "" : ": " + details));
            }
            return response;
        } catch (IOException error) {
            CrashReporter.logHttpFailure("POST", urlStr, error);
            // Only a failed connection is dropped; a fully read one returns to the keep-alive pool.
            if (conn != null) conn.disconnect();
            throw error;
        }
    }

    public static String getUrl(String urlStr, String userAgent) throws IOException {
        return getUrl(urlStr, userAgent, 10000, 15000);
    }

    public static String getUrl(String urlStr, String userAgent,
                                int connectTimeout, int readTimeout) throws IOException {
        CrashReporter.logHttpRequest("GET", urlStr, null);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            int status = conn.getResponseCode();
            InputStream responseStream = status >= HttpURLConnection.HTTP_OK
                    && status < HttpURLConnection.HTTP_MULT_CHOICE
                    ? conn.getInputStream() : conn.getErrorStream();
            String response = responseStream == null ? "" : readStream(decode(conn, responseStream));
            CrashReporter.logHttpResponse("GET", urlStr, status, response);
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("HTTP " + status + " from " + urlStr);
            }
            return response;
        } catch (IOException error) {
            CrashReporter.logHttpFailure("GET", urlStr, error);
            if (conn != null) conn.disconnect();
            throw error;
        }
    }

    /**
     * Downloads bytes {@code start}..{@code end} (inclusive) of a media URL. Used by the
     * experimental muxer; the response bodies are not written to the crash log.
     */
    public static byte[] getRange(String urlStr, long start, long end, String userAgent)
            throws IOException {
        int expected = (int) (end - start + 1);
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                int status = conn.getResponseCode();
                if (status != HttpURLConnection.HTTP_PARTIAL && status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + status + " for media range");
                }
                InputStream in = conn.getInputStream();
                try {
                    if (status == HttpURLConnection.HTTP_OK && start > 0) {
                        long skipped = 0;
                        while (skipped < start) {
                            long n = in.skip(start - skipped);
                            if (n <= 0) throw new IOException("Could not skip to media range");
                            skipped += n;
                        }
                    }
                    byte[] data = new byte[expected];
                    int read = 0;
                    while (read < expected) {
                        int n = in.read(data, read, expected - read);
                        if (n < 0) break;
                        read += n;
                    }
                    if (read != expected) throw new IOException("Short media range: " + read + "/" + expected);
                    return data;
                } finally {
                    in.close();
                }
            } catch (IOException error) {
                last = error;
                if (conn != null) conn.disconnect();
            }
        }
        CrashReporter.logHttpFailure("GET", urlStr, last);
        throw last;
    }

    /**
     * Requesting gzip explicitly turns off HttpURLConnection's transparent decompression
     * (Android 2.3 does not do it reliably), so the body is unpacked here.
     */
    private static InputStream decode(HttpURLConnection conn, InputStream in) throws IOException {
        String encoding = conn.getContentEncoding();
        return encoding != null && encoding.toLowerCase(java.util.Locale.US).contains("gzip")
                ? new java.util.zip.GZIPInputStream(in, 16384) : in;
    }

    private static String readStream(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[16384];
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

    /**
     * Stores a finished feed. A feed built before all metadata arrived is stored as already
     * expired: it is not served as fresh (the next request rebuilds it from warm caches), but
     * it is still there as a fallback when YouTube cannot be reached.
     */
    public static void writeFeedCache(File f, String content, boolean complete, long maxAgeMs) {
        try {
            writeFile(f, content);
            if (!complete) f.setLastModified(System.currentTimeMillis() - maxAgeMs - 1000);
        } catch (IOException ignored) { }
    }

    public static void writeFile(File f, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }
}
