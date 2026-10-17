package com.viitube.proxy;

import android.app.Application;

public class CrashApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.initialize(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashReporter.recordCrash(thread, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}