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
