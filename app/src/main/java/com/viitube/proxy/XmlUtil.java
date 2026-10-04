package com.viitube.proxy;

import java.util.Map;

/**
 * Escaping XML + bardzo prosty silnik szablonów: podmienia {{token}} na wartości z mapy.
 * Dzięki temu format odpowiedzi (assets/templates/*.xml) da się edytować bez rekompilacji.
 */
public class XmlUtil {

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** Podmienia {{key}} na escapowaną wartość - używać dla zwykłego tekstu (tytuły, opisy itp.). */
    public static String render(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", escape(e.getValue()));
        }
        return out;
    }

    /** Jak render(), ale bez escapowania - do wklejania już gotowych fragmentów XML (np. zagnieżdżonych entry). */
    public static String renderRaw(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }
}
