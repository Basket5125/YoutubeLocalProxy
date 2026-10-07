package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

import java.util.ArrayList;
import java.util.List;

/**
 * Router HTTP. Ścieżki rejestrujesz jak we Flasku, "*" = jeden dowolny segment (odpowiednik <param>).
 * Priorytet: registerDevice i most_popular muszą działać, żeby appka w ogóle wystartowała.
 */
public class ProxyServer extends NanoHTTPD {

    private final Context context;
    private final Config config;
    private final List<Route> routes = new ArrayList<Route>();

    private static class Route {
        final String[] pattern;
        final Endpoint endpoint;

        Route(String path, Endpoint e) {
            pattern = path.replaceAll("^/", "").split("/");
            endpoint = e;
        }
    }

    public ProxyServer(int port, Context context, Config config) {
        super(port);
        this.context = context;
        this.config = config;
        registerRoutes();
    }

    public void register(String path, Endpoint endpoint) {
        routes.add(new Route(path, endpoint));
    }

    private void registerRoutes() {
        // --- priorytet: appka musi z tym wystartować ---
        register("/youtube/accounts/registerDevice", new RegisterDeviceEndpoint());
        register("/proxy/ytbt", new RegisterDeviceEndpoint());
        register("/feeds/api/standardfeeds/*/most_popular", new MostPopularEndpoint());
        register("/feeds/api/videos", new InnertubeEndpoint("search"));
        register("/complete/search", new InnertubeEndpoint("suggestions"));
        register("/feeds/api/videos/*/comments", new InnertubeEndpoint("comments"));
        register("/feeds/api/videos/*/related", new InnertubeEndpoint("related"));
        register("/feeds/api/users/*/uploads", new ChannelEndpoint("uploads"));
        register("/feeds/api/users/*/playlists", new ChannelEndpoint("playlists"));
        register("/feeds/api/users/*", new ChannelEndpoint("channel"));
        register("/feeds/api/playlists/*", new PlaylistEndpoint());
        register("/video/*", new VideoRedirectEndpoint());

        // --- odtwarzanie: metadata + redirect na strumień (bez transkodowania) ---
        register("/feeds/api/videos/*", new VideoInfoEndpoint());
        register("/get_video", new VideoRedirectEndpoint());
        register("/channel_fh264_getvideo", new VideoRedirectEndpoint());
        register("/get_480", new VideoRedirectEndpoint());
        register("/exp_hd", new VideoRedirectEndpoint());

        // --- reszta z oryginalnego skryptu: na razie "stub", dopisz wg wzorca z README ---
        StubEndpoint stub = new StubEndpoint();
        String[] notImplementedYet = {
                "/feeds/api/users/*/icon",
                "/feeds/api/channels",
                "/feeds/tv/users/default",
                "/feeds/tv/users/default/subscriptions",
                "/feeds/tv/users/default/favorites",
                "/feeds/tv/users/default/playlists",
                "/feeds/tv/users/default/watch_later",
                "/feeds/tv/users/default/watch_history",
                "/feeds/api/users/default/watch_history"
        };
        for (String p : notImplementedYet) {
            register(p, stub);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String[] segments = uri.replaceAll("^/", "").split("/");

        for (Route r : routes) {
            String[] captured = match(r.pattern, segments);
            if (captured != null) {
                try {
                    return r.endpoint.handle(session, captured, config, context);
                } catch (Exception e) {
                    String error = safeErrorMessage(e);
                    android.util.Log.e("ProxyServer", "Request failed for " + uri + ": " + error);
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                            "error: " + error);
                }
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: " + uri);
    }

    private String safeErrorMessage(Throwable error) {
        StringBuilder message = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (message.length() > 0) message.append(" <- ");
            message.append(current.getClass().getSimpleName());
            String detail = current.getMessage();
            if (detail != null && !detail.isEmpty()) {
                detail = redact(detail, config.getInnertubeApiKey());
                detail = redact(detail, config.getYouTubeApiKey());
                message.append(": ").append(detail);
            }
            current = current.getCause();
        }
        return message.toString();
    }

    private static String redact(String value, String secret) {
        return secret == null || secret.isEmpty() ? value : value.replace(secret, "[redacted]");
    }

    private String[] match(String[] pattern, String[] segments) {
        if (pattern.length != segments.length) {
            return null;
        }
        List<String> captured = new ArrayList<String>();
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].equals("*")) {
                captured.add(segments[i]);
            } else if (!pattern[i].equals(segments[i])) {
                return null;
            }
        }
        return captured.toArray(new String[0]);
    }
}
