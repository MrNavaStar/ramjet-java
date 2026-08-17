package me.mrnavastar.ramjet.util;

import java.net.http.HttpClient;

public class Http {

    public static final HttpClient INSTANCE = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
}
