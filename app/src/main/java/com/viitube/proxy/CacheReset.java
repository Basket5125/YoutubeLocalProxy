package com.viitube.proxy;

import android.content.Context;

import java.io.File;

/**
 * Empties every cache of the proxy: the feeds, player answers and protobuf answers kept in
 * memory, and the files under the app's cache directory. Nothing else is touched - the settings
 * and a crash report waiting to be sent live elsewhere - so the next request simply asks YouTube
 * again. Used by the Clear cache button.
 */
final class CacheReset {

    static final class Result {
        int files;
        long bytes;

        String describe() {
            if (files == 0) return "Cache cleared";
            long kb = (bytes + 1023) / 1024;
            return "Cache cleared: " + files + (files == 1 ? " file, " : " files, ")
                    + (kb >= 1024 ? (bytes / (1024 * 1024)) + " MB" : kb + " KB");
        }
    }

    private CacheReset() { }

    static Result clear(Context context) {
        YoutubeiEndpoint.clearMemory();
        InnertubeEndpoint.clearMemory();
        PlaylistEndpoint.clearMemory();
        ChannelEndpoint.clearMemory();
        PlayerLookup.clearPlayerCache(context);
        PlayerLookup.clearMetadataCache();
        InvidiousLookup.clearMemory();
        Result result = new Result();
        File cacheDir = context.getCacheDir();
        if (cacheDir != null) deleteContents(cacheDir, result);
        android.util.Log.i("CacheReset", result.describe());
        return result;
    }

    /** Deletes what is inside the directory, keeping the directory itself. */
    private static void deleteContents(File directory, Result result) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                deleteContents(child, result);
                child.delete();
            } else {
                long size = child.length();
                if (child.delete()) {
                    result.files++;
                    result.bytes += size;
                }
            }
        }
    }
}
