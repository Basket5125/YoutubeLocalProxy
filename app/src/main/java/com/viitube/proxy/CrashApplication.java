package com.viitube.proxy;

import android.app.Application;

public class CrashApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // A feed fans out to ten lookups at once; with the five kept connections of the default
        // every one past that closes its connection and pays for a new TLS handshake.
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "12");
        CrashReporter.initialize(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashReporter.recordCrash(thread, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /**
     * Android is short of memory. What this app holds is caches, every one of which can be read
     * again, so letting go of them here is what keeps the proxy alive - and running - instead of
     * being killed in the middle of a video.
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        CacheReset.trimMemory(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) CacheReset.trimMemory(this);
    }
}