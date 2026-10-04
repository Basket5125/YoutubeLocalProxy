package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Odpowiednik get_video_xml() z Pythona: zwraca metadane wideo jako XML entry.
 * media:content wewnątrz wskazuje na /get_video?video_id=... , który robi redirect na googlevideo.com.
 */
public class VideoInfoEndpoint implements Endpoint {

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                      Config config, Context context) throws Exception {
        String videoId = params[0];
        JSONObject player = PlayerLookup.fetch(videoId, config, context);
        JSONObject details = player.optJSONObject("videoDetails");

        if (details == null) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain", "video not found");
        }

        String host = session.getHeaders().get("host");

        Map<String, String> vars = new HashMap<String, String>();
        vars.put("videoId", videoId);
        vars.put("title", details.optString("title", ""));
        vars.put("description", details.optString("shortDescription", ""));
        vars.put("author", details.optString("author", ""));
        vars.put("channelId", details.optString("channelId", ""));
        vars.put("viewCount", details.optString("viewCount", "0"));
        vars.put("lengthSeconds", details.optString("lengthSeconds", "0"));
        vars.put("baseUrl", "http://" + (host != null ? host : "127.0.0.1"));

        String template = TemplateStore.load(context, "video_entry.xml");
        String xml = XmlUtil.render(template, vars);

        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/atom+xml", xml);
    }
}
