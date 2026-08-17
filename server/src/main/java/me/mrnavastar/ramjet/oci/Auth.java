package me.mrnavastar.ramjet.oci;

import me.mrnavastar.ramjet.util.Http;
import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.result.Fate;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class Auth {

    private static final ConcurrentHashMap<String, CompletableFuture<Token>> tokens = new ConcurrentHashMap<>();

    private record Token(
            String token,
            long expiresAt
    ) {
        boolean valid() {
            // Refresh slightly before actual expiry
            return System.currentTimeMillis() < expiresAt - 30_000;
        }
    }

    // EXAMPLE: Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:library/ubuntu:pull"
    private static Map<String, String> parseAuthHeader(String header) {
        Map<String, String> map = new HashMap<>();

        // Remove Quotes, Remove "Bearer " prefix & Split key-value pairs
        String[] parts = header.replace("\"", "").substring(header.indexOf(' ') + 1).split(",");

        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    private static Fate<Token> fetchToken(HttpRequest.Builder request) {
        return Fate.of(() -> Http.INSTANCE.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()))

            .flatMap(ping -> Fate.of(ping.headers().firstValue("www-authenticate"))
                    .map(Auth::parseAuthHeader)
                    .map(header -> Http.INSTANCE.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(header.get("realm")
                                            + "?service=" + URLEncoder.encode(header.get("service"), StandardCharsets.UTF_8)
                                            + "&scope=" + URLEncoder.encode(header.get("scope"), StandardCharsets.UTF_8)))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream()
                    )))
            .map(response -> {
                if (response.statusCode() != 200) throw new IOException("Token request failed: " + response.statusCode());

                var json = Mapper.INSTANCE.readTree(response.body());
                long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong() : 300;
                return new Token(json.get("token").asText(), System.currentTimeMillis() + expiresIn * 1000);
            });
    }

    public static Fate<CompletableFuture<Token>> getToken(String authHeader) {
        Map<String, String> parts = parseAuthHeader(authHeader);

        return Fate.of(() -> {
            String realm = parts.get("realm");
            String service = parts.get("service");
            String scope = parts.get("scope");

            String key = realm + "\n" + service + "\n" + scope;

            CompletableFuture<Token> cached = tokens.computeIfAbsent(key, k -> {


                CompletableFuture<Token> t = new CompletableFuture<>();

                tokens.put(key, t);
                t.complete(new Token();
            });
            if (cached != null) return cached;




            return t;
        });
}
