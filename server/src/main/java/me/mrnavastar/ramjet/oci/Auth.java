package me.mrnavastar.ramjet.oci;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
public class Auth {

    private static final ConcurrentHashMap<String, CompletableFuture<Token>> tokens = new ConcurrentHashMap<>();

    public record Token(
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
            System.out.println(part);
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    // TODO: If no www-authenticate header is returned, it likely means we do not need auth, so we should handle this case and return a null token
    private static Fate<Token> fetchToken(String host, String repo) {
        return Fate.of(() -> Http.INSTANCE.send(
                HttpRequest.newBuilder(URI.create("https://" + host + "/v2/" + repo)).build(),
                HttpResponse.BodyHandlers.ofInputStream()))

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

    public static CompletableFuture<Token> getToken(String host, String repo) {
        var future = new CompletableFuture<Token>();
        var existing = tokens.putIfAbsent(host, future);

        if (existing != null) {
            if (existing.isDone() && !Fate.of(() -> existing.get().valid()).orElse(false)) tokens.put(host, future);
            else return existing;
        }

        fetchToken(host, repo)
            .peekErr(e -> {
                tokens.remove(host);
                log.warn("failed to fetch oci registry token for: {}\n{}", host, e.toString());
                e.printStackTrace();
            })
            .map(future::complete);

        return future;
    }
}