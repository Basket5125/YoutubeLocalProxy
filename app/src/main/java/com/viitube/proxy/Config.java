package com.viitube.proxy;

import android.content.Context;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;

/**
 * Wczytuje /files/config.json (kopiowany z assets/config.json przy pierwszym uruchomieniu).
 * Można nadpisać wartości bez rekompilacji appki, np. przez `adb push nowy_config.json
 * /data/data/com.viitube.proxy/files/config.json`.
 */
public class Config {

    public String getYouTubeApiKey() { return data.optString("youtube_api_key", ""); }
    public String getInnertubeApiKey() { return data.optString("innertube_api_key", "").trim(); }
    public void setYouTubeApiKey(String key) {
        try { data.put("youtube_api_key", key == null ? "" : key.trim()); }
        catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    public void setBool(String key, boolean value) {
        try { data.put(key, value); }
        catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized void save() throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configFile), "UTF-8");
        try {
            writer.write(data.toString(2));
        } catch (org.json.JSONException e) {
            throw new IOException("Could not serialize configuration", e);
        } finally {
            writer.close();
        }
    }

    private static Config instance;
    private JSONObject data;
    private final File configFile;

    private Config(Context context) {
        configFile = new File(context.getFilesDir(), "config.json");
        try {
            if (!configFile.exists()) {
                copyDefaultConfig(context);
            }
            data = new JSONObject(readFile(configFile));
        } catch (Exception e) {
            data = new JSONObject();
        }
    }

    public static synchronized Config get(Context context) {
        if (instance == null) {
            instance = new Config(context.getApplicationContext());
        }
        return instance;
    }

    private void copyDefaultConfig(Context context) throws IOException {
        InputStream in = context.getAssets().open("config.json");
        FileOutputStream out = new FileOutputStream(configFile);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        out.close();
    }

    private String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new FileReader(f));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append("\n");
        }
        r.close();
        return sb.toString();
    }

    public String getString(String key, String def) {
        return data.optString(key, def);
    }

    public int getInt(String key, int def) {
        return data.optInt(key, def);
    }

    public boolean getBool(String key, boolean def) {
        return data.optBoolean(key, def);
    }
}
