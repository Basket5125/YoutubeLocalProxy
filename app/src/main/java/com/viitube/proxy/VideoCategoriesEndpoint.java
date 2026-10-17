package com.viitube.proxy;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;

public class VideoCategoriesEndpoint implements Endpoint {

    @Override
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session, String[] params,
                                     Config config, Context context) {
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?>")
                .append("<feed xmlns='http://www.w3.org/2005/Atom'")
                .append(" xmlns:openSearch='http://a9.com/-/spec/opensearch/1.1/'")
                .append(" xmlns:yt='http://gdata.youtube.com/schemas/2007'>")
                .append("<id>http://gdata.youtube.com/feeds/api/videoCategories</id>")
                .append("<title type='text'>YouTube video categories</title>")
                .append("<openSearch:totalResults>").append(MostPopularEndpoint.CATEGORIES.length)
                .append("</openSearch:totalResults>");
        for (String[] category : MostPopularEndpoint.CATEGORIES) {
            String name = XmlUtil.escape(category[1]);
            xml.append("<entry><id>http://gdata.youtube.com/schemas/2007#")
                    .append(category[0]).append("</id><category scheme='")
                    .append("http://gdata.youtube.com/schemas/2007/categories.cat' term='")
                    .append(category[0]).append("' label='").append(name).append("'/>")
                    .append("<title type='text'>").append(name).append("</title>")
                    .append("<yt:videoCategoryId>").append(category[0])
                    .append("</yt:videoCategoryId></entry>");
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "application/atom+xml; charset=utf-8", xml.append("</feed>").toString());
    }
}