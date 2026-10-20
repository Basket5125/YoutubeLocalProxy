package com.viitube.proxy;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class CrashReporter {

    private static final String REPORT_URL = "http://crashlogs.truehosting.net/ytproxy";
    private static final int MAX_LOG_BYTES = 1024 * 1024;
    private static final int MAX_EVENT_CHARS = 48 * 1024;
    private static Context appContext;

    private CrashReporter() { }

    public static synchronized void initialize(Context context) {
        appContext = context.getApplicationContext();
    }

    public static void recordCrash(Thread thread, Throwable error) {
        Context context = appContext;
        if (context == null) return;
        // The events closest to the crash are the interesting ones, so the log is given a moment
        // to catch up before the report is written. The app is going down anyway.
        try {
            LOG_WRITER.shutdown();
            LOG_WRITER.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        try {
            JSONObject crash = new JSONObject();
            crash.put("time", timestamp());
            crash.put("thread", thread == null ? "unknown" : thread.getName());
            crash.put("exception", error.getClass().getName());
            crash.put("message", clip(sanitize(error.toString()), MAX_EVENT_CHARS));
            StringBuilder stack = new StringBuilder();
            for (StackTraceElement element : error.getStackTrace()) {
                stack.append(element.toString()).append('\n');
                if (stack.length() >= MAX_EVENT_CHARS) break;
            }
            crash.put("stack", clip(sanitize(stack.toString()), MAX_EVENT_CHARS));
            writeAtomically(new File(context.getFilesDir(), "crash-report.json"), crash.toString());
        } catch (Exception ignored) {
        }
    }

    /**
     * The request log is written by one background thread. Sanitizing and appending a response of
     * tens of kilobytes used to happen on the thread that made the request, under one lock, so
     * every YouTube call waited for the previous one's log to reach the flash storage. Only the
     * clipping stays here, so a queued event cannot hold a whole response in memory; when the
     * queue is full the event is dropped, because a log is worth less than a fast answer.
     */
    private static final java.util.concurrent.ThreadPoolExecutor LOG_WRITER =
            new java.util.concurrent.ThreadPoolExecutor(0, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable task) {
                            Thread thread = new Thread(task, "crash-log");
                            thread.setDaemon(true);
                            thread.setPriority(Thread.MIN_PRIORITY);
                            return thread;
                        }
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    public static void logHttpRequest(String method, String url, String body) {
        log("request", method, url, clip(body, MAX_EVENT_CHARS), null, null);
    }

    public static void logHttpResponse(String method, String url, int status, String body) {
        log("response", method, url, null, Integer.valueOf(status), clip(body, MAX_EVENT_CHARS));
    }

    public static void logHttpFailure(String method, String url, Throwable error) {
        log("failure", method, url, null, null, error.toString());
    }

    private static void log(final String type, final String method, final String url,
                            final String requestBody, final Integer status, final String responseBody) {
        if (appContext == null) return;
        LOG_WRITER.execute(new Runnable() {
            @Override
            public void run() {
                appendEvent(type, method, url, requestBody, status, responseBody);
            }
        });
    }

    private static synchronized void appendEvent(String type, String method, String url,
                                                 String requestBody, Integer status,
                                                 String responseBody) {
        Context context = appContext;
        if (context == null) return;
        try {
            File dir = new File(context.getFilesDir(), "crash-logs");
            if (!dir.exists() && !dir.mkdirs()) return;
            File log = new File(dir, "requests.jsonl");
            if (log.exists() && log.length() >= MAX_LOG_BYTES) {
                File previous = new File(dir, "requests.previous.jsonl");
                if (previous.exists()) previous.delete();
                if (!log.renameTo(previous)) log.delete();
            }
            JSONObject event = new JSONObject();
            event.put("time", timestamp());
            event.put("type", type);
            event.put("method", method);
            event.put("url", sanitizeUrl(url));
            if (requestBody != null) event.put("request", sanitize(requestBody));
            if (status != null) event.put("status", status);
            if (responseBody != null) event.put("response", sanitize(responseBody));
            byte[] line = (event.toString() + "\n").getBytes("UTF-8");
            if (log.length() + line.length > MAX_LOG_BYTES) return;
            FileOutputStream out = new FileOutputStream(log, true);
            try {
                // No fsync per event: on old flash storage it blocked every proxied request.
                out.write(line);
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean hasPendingCrash(Context context) {
        return new File(context.getFilesDir(), "crash-report.json").isFile();
    }

    public static void upload(Context context, UploadCallback callback) {
        HttpURLConnection connection = null;
        try {
            JSONObject report = new JSONObject();
            report.put("app", appInfo(context));
            report.put("device", deviceInfo());
            report.put("crash", new JSONObject(readFile(
                    new File(context.getFilesDir(), "crash-report.json"))));
            report.put("httpLog", readLogs(context));

            connection = (HttpURLConnection) new URL(REPORT_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(report.toString().getBytes("UTF-8"));
            } finally {
                output.close();
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Report server returned HTTP " + status);
            }
            clearLogs(context);
            callback.onComplete(null);
        } catch (Exception error) {
            android.util.Log.e("CrashReporter", "Could not upload crash report", error);
            callback.onComplete(error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject appInfo(Context context) throws JSONException {
        JSONObject app = new JSONObject().put("package", context.getPackageName());
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            app.put("version", info.versionName);
            app.put("versionCode", info.versionCode);
        } catch (Exception ignored) {
        }
        return app;
    }

    private static JSONObject deviceInfo() throws JSONException {
        return new JSONObject().put("manufacturer", Build.MANUFACTURER)
                .put("brand", Build.BRAND)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("androidSdk", Build.VERSION.SDK_INT);
    }

    private static String readLogs(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "crash-logs");
        StringBuilder logs = new StringBuilder();
        appendFile(logs, new File(dir, "requests.previous.jsonl"));
        appendFile(logs, new File(dir, "requests.jsonl"));
        return logs.toString();
    }

    private static void appendFile(StringBuilder target, File file) throws Exception {
        if (!file.isFile()) return;
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), "UTF-8"));
        try {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) target.append(buffer, 0, count);
        } finally {
            reader.close();
        }
    }

    private static String readFile(File file) throws Exception {
        InputStream input = new FileInputStream(file);
        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) content.append(buffer, 0, count);
            return content.toString();
        } finally {
            input.close();
        }
    }

    private static void clearLogs(Context context) {
        new File(context.getFilesDir(), "crash-report.json").delete();
        File dir = new File(context.getFilesDir(), "crash-logs");
        new File(dir, "requests.jsonl").delete();
        new File(dir, "requests.previous.jsonl").delete();
    }

    private static void writeAtomically(File destination, String content) throws Exception {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        FileOutputStream output = new FileOutputStream(temporary);
        try {
            output.write(content.getBytes("UTF-8"));
            output.getFD().sync();
        } finally {
            output.close();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new java.io.IOException("Could not save crash report");
        }
    }

    private static String sanitizeUrl(String value) {
        return value.replaceAll("(?i)([?&](?:key|token|access_token|signature|sig|visitor_data)=)[^&#]*",
                "$1[redacted]");
    }

    private static String sanitize(String value) {
        return value.replaceAll("(?i)(\\\"(?:key|authorization|cookie|visitorData|access_token|refresh_token|signatureCipher)\\\"\\s*:\\s*\\\")[^\\\"]*",
                "$1[redacted]")
                .replaceAll("(?i)([?&](?:key|token|access_token|signature|sig|lsig|expire|sparams|visitor_data|n)=)[^&#\\\"\\s]*",
                "$1[redacted]");
    }

    private static String clip(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit) + " [truncated]";
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    public interface UploadCallback {
        void onComplete(Exception error);
    }
}