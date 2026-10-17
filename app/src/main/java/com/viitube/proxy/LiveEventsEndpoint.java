package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

public class LiveEventsEndpoint implements Endpoint {

    private static final int DEFAULT_MAX_RESULTS = 18;
    private static final int MAX_RESULTS = 50;

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) throws Exception {
        int maxResults = parsePositiveInt(session.getParms().get("max-results"),
                DEFAULT_MAX_RESULTS, MAX_RESULTS);
        String host = session.getHeaders().get("host");
        String baseUrl = "http://" + (host == null || host.isEmpty() ? "127.0.0.1" : host);
        String feed = InnertubeEndpoint.fetchLiveEventsFeed(maxResults, config, baseUrl);
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/atom+xml; charset=utf-8", feed);
    }

    private static int parsePositiveInt(String value, int fallback, int maximum) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= maximum ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}