package com.viitube.proxy;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wczytuje szablon XML: najpierw sprawdza /files/templates/<name> (edytowalne np. przez adb push
 * bez rekompilacji), inaczej bierze domyślny z assets/templates/<name>.
 */
public class TemplateStore {

    /** An asset that is not a template, read straight out of the app. */
    public static String loadRaw(Context context, String name) {
        try {
            InputStream in = context.getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (IOException e) {
            android.util.Log.w("TemplateStore", "Could not read " + name + ": " + e);
            return "";
        }
    }

    public static String load(Context context, String name) throws IOException {
        File custom = new File(new File(context.getFilesDir(), "templates"), name);
        if (custom.exists()) {
            return HttpClient.readFile(custom);
        }

        InputStream in = context.getAssets().open("templates/" + name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toString("UTF-8");
    }
}
