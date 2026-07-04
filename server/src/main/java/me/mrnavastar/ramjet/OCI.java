package me.mrnavastar.ramjet;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import lombok.Getter;
import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.VerifyingInputStream;
import me.mrnavastar.ramjet.util.result.Fate;

// TODO: Token Caching
public class OCI {

    private static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    // EXAMPLE: Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:library/ubuntu:pull"
    public static Map<String, String> parseAuthHeader(String header) {
        Map<String, String> map = new LinkedHashMap<>();

        // Remove Quotes, Remove "Bearer " prefix & Split key-value pairs
        String[] parts = header.replace("\"", "").substring(header.indexOf(' ') + 1).split(",");

        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    private static String fetchToken(String authHeader) throws Exception {
        Map<String, String> parts = parseAuthHeader(authHeader);

        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
            .uri(new URI(parts.get("realm") + "?service=" + URLEncoder.encode(parts.get("service"), StandardCharsets.UTF_8) + "&scope=" + URLEncoder.encode(parts.get("scope"), StandardCharsets.UTF_8)))
                .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) throw new IOException("Token request failed: " + response.statusCode());
        return Mapper.INSTANCE.readTree(response.body()).get("token").asText();
    }

    private static HttpResponse<InputStream> getWithAuth(HttpRequest.Builder request) throws Exception {
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 401)
            response = http.send(request
                    .header("Authorization", "Bearer " + fetchToken(response.headers().firstValue("www-authenticate").orElseThrow(() -> new IOException("Missing WWW-Authenticate"))))
                    .build(),
                    HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) throw new IOException("Request failed: " + response.statusCode());
        return response;
    }

    private static Manifest manifest(String host, String repository, String tag) throws Exception {
        HttpResponse<InputStream> response = getWithAuth(HttpRequest.newBuilder()
                        .uri(URI.create("https://" + host + "/v2/" + repository + "/manifests/" + tag))
                        .header("Accept", "application/vnd.oci.image.manifest.v1+json").GET());
        if (response.statusCode() != 200) throw new IOException("Manifest request failed: " + response.statusCode());
        return Mapper.INSTANCE.readValue(response.body(), Manifest.class);
    }

    public static Fate<VerifyingInputStream> blob(String host, String repository, String digest) {
        return Fate.of(() -> {
            HttpResponse<InputStream> response = getWithAuth(HttpRequest.newBuilder()
                            .uri(URI.create("https://" + host + "/v2/" + repository + "/blobs/" + digest))
                            .GET());
            if (response.statusCode() != 200) throw new IOException("Blob request failed: " + response.statusCode());
            return new VerifyingInputStream(response.body(), digest);
        });
    }

    public record Descriptor(
            String digest
    ) {}

    public record Manifest(
            Descriptor config,
            List<Descriptor> layers
    ) {}

    @Getter
    public static class Image {
        private final URI uri;
        private final String repo;
        private final Manifest manifest;
        private final ImageConfig config;

        private Image(URI uri) throws Exception {
            var parts = uri.getPath().split(":");
            this.uri = uri;
            this.repo = parts[0];
            String tag = parts[1];
            manifest = manifest(uri.getHost(), repo, tag);
            config = Mapper.INSTANCE.readValue(blob(uri.getHost(), repo, manifest.config.digest()).resolve(), ImageConfig.class);
        }

        public static Fate<Image> New(URI uri) {
            return Fate.of(() -> new Image(uri));
        }
    }
}