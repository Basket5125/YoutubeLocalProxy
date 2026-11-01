package com.viitube.proxy;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mints the proof-of-origin token YouTube now wants with a stream. The token is made by a
 * program of YouTube's own that only runs in a browser, so a hidden WebView runs it: the page
 * is loaded with www.youtube.com as its address, asks for the program, runs it and mints a
 * token for one identifier - the video a stream belongs to, or the visitor of a session.
 *
 * <p>Without a token the server serves the first minute of a video and nothing more. The page
 * needs a current WebView, which is why this is only used from Android 6 on.
 */
final class PoTokenMinter {

    private static final String TAG = "PoToken";
    /** Android 6; the WebView of older releases cannot run the program. */
    private static final int MIN_ANDROID = Build.VERSION_CODES.M;
    /** A token is good for hours; this is how long one is kept. */
    private static final long MAX_AGE_MS = 6L * 60 * 60 * 1000;
    private static final long MINT_TIMEOUT_MS = 40000;

    private static PoTokenMinter instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> tokens = new HashMap<String, String>();
    private final Map<String, Long> minted = new HashMap<String, Long>();
    private final Map<String, Waiter> waiting = new HashMap<String, Waiter>();
    private WebView page;
    private boolean ready;
    private boolean broken;
    private int nextId;

    private PoTokenMinter(Context context) {
        this.context = context.getApplicationContext();
    }

    static synchronized PoTokenMinter get(Context context) {
        if (instance == null) instance = new PoTokenMinter(context);
        return instance;
    }

    /** Whether this phone can run the program at all. */
    static boolean supported(Config config) {
        // Off unless it is asked for: what it unlocks needs the browser's own links, which this
        // proxy cannot sign, so with it on nothing is gained and the phone does extra work.
        return Build.VERSION.SDK_INT >= MIN_ANDROID && config.getBool("po_token", false);
    }

    /** One caller waiting for its token. */
    private static final class Waiter {
        final CountDownLatch done = new CountDownLatch(1);
        String token = "";
        String error = "";
    }

    /**
     * The token for an identifier - a video id for a stream, the visitor id for a session - or
     * "" when this phone cannot mint one. Kept for six hours, and minted once at a time.
     */
    String token(String identifier) {
        if (Build.VERSION.SDK_INT < MIN_ANDROID || broken || identifier == null
                || identifier.isEmpty()) {
            return "";
        }
        synchronized (this) {
            String known = tokens.get(identifier);
            Long when = minted.get(identifier);
            if (known != null && when != null
                    && System.currentTimeMillis() - when.longValue() < MAX_AGE_MS) {
                return known;
            }
        }
        try {
            return mint(identifier);
        } catch (Exception e) {
            android.util.Log.w(TAG, "No token for " + identifier + ": " + e);
            return "";
        }
    }

    private synchronized String mint(String identifier) throws InterruptedException {
        Waiter waiter = new Waiter();
        String callbackId;
        synchronized (waiting) {
            callbackId = Integer.toString(++nextId);
            waiting.put(callbackId, waiter);
        }
        main.post(new Mint(this, identifier, callbackId));
        if (!waiter.done.await(MINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            synchronized (waiting) {
                waiting.remove(callbackId);
            }
            android.util.Log.w(TAG, "the page did not answer in time");
            return "";
        }
        if (!waiter.error.isEmpty()) {
            android.util.Log.w(TAG, "the page could not mint a token: " + waiter.error);
            return "";
        }
        synchronized (this) {
            tokens.put(identifier, waiter.token);
            minted.put(identifier, Long.valueOf(System.currentTimeMillis()));
        }
        android.util.Log.i(TAG, "minted a token for " + identifier
                + " (" + waiter.token.length() + " characters)");
        return waiter.token;
    }

    /** Runs on the interface thread, which is the only place a WebView may be touched. */
    private static final class Mint implements Runnable {
        private final PoTokenMinter minter;
        private final String identifier;
        private final String callbackId;

        Mint(PoTokenMinter minter, String identifier, String callbackId) {
            this.minter = minter;
            this.identifier = identifier;
            this.callbackId = callbackId;
        }

        @Override
        public void run() {
            try {
                minter.open();
                minter.call(identifier, callbackId);
            } catch (Throwable error) {
                minter.fail(callbackId, String.valueOf(error));
            }
        }
    }

    /** Builds the page once; it stays loaded, so later tokens cost one call. */
    private void open() {
        if (page != null) return;
        WebView webView = new WebView(context);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new Bridge(this), "PoTokenBridge");
        String html = TemplateStore.loadRaw(context, "po_token.html");
        webView.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null);
        page = webView;
    }

    private void call(String identifier, String callbackId) {
        if (page == null) {
            fail(callbackId, "no page");
            return;
        }
        if (!ready) {
            // The page says when it is ready; until then the call waits a moment and tries again.
            main.postDelayed(new Mint(this, identifier, callbackId), 250);
            return;
        }
        String script = "mintPoToken(" + quote(identifier) + "," + quote(callbackId) + ");";
        page.evaluateJavascript(script, null);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    void fail(String callbackId, String message) {
        Waiter waiter;
        synchronized (waiting) {
            waiter = waiting.remove(callbackId);
        }
        if (waiter == null) return;
        waiter.error = message;
        waiter.done.countDown();
    }

    void succeed(String callbackId, String token) {
        Waiter waiter;
        synchronized (waiting) {
            waiter = waiting.remove(callbackId);
        }
        if (waiter == null) return;
        waiter.token = token == null ? "" : token;
        waiter.done.countDown();
    }

    void pageReady() {
        ready = true;
    }

    /** What the page calls back into. */
    public static final class Bridge {
        private final PoTokenMinter minter;

        Bridge(PoTokenMinter minter) {
            this.minter = minter;
        }

        @JavascriptInterface
        public void onReady() {
            minter.pageReady();
        }

        @JavascriptInterface
        public void onToken(String callbackId, String token) {
            minter.succeed(callbackId, token);
        }

        @JavascriptInterface
        public void onError(String callbackId, String message) {
            minter.fail(callbackId, message);
        }
    }
}
