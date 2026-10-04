package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

/**
 * Wzorzec do dopisywania kolejnych endpointów z oryginalnego skryptu Python.
 * pathParams - wartości podstawione za "*" we wzorcu ścieżki (patrz ProxyServer.register()).
 */
public interface Endpoint {
    NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] pathParams,
                               Config config, Context context) throws Exception;
}
