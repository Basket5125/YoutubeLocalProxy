package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

/**
 * Placeholder dla endpointow z oryginalnego skryptu, ktore nie sa jeszcze przeniesione.
 * Zwraca pusty ale poprawny XML, zeby klient sie nie wywalil. Zeby dopisac prawdziwa logike:
 * zobacz README ("Jak dopisac kolejny endpoint") i podmien rejestracje w ProxyServer.
 */
public class StubEndpoint implements Endpoint {

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                      Config config, Context context) {
        String empty = "<?xml version='1.0' encoding='UTF-8'?><feed></feed>";
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/atom+xml", empty);
    }
}
