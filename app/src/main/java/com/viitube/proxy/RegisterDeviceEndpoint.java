package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

import java.util.Random;

/**
 * Odpowiednik upload_hex()/register_device() z Pythona - musi odpowiedzieć,
 * żeby spatchowana appka YouTube w ogóle wystartowała.
 */
public class RegisterDeviceEndpoint implements Endpoint {

    private static final String CHARSET = "qwertyuiopasdfghjklzxcvbnm1234567890";

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                      Config config, Context context) {
        String deviceId = randomId(7);
        String body = "DeviceId=" + deviceId + "\nDeviceKey=ULxlVAAVMhZ2GeqZA/X1GgqEEIP1ibcd3S+42pkWfmk=";
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", body);
    }

    private String randomId(int len) {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(CHARSET.charAt(r.nextInt(CHARSET.length())));
        }
        return sb.toString();
    }
}
