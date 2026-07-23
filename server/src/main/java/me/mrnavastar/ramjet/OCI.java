package me.mrnavastar.ramjet;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.VerifyingInputStream;
import me.mrnavastar.ramjet.util.result.Fate;

// TODO: Token Caching
public class OCI {

    public static class OCIResolutionException extends Exception {
        public OCIResolutionException(String msg) {
            super(String.format("Failed to resolve OCI object: %s", msg));
        }
    }

    private static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    // EXAMPLE: Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:library/ubuntu:pull"
    public static Map<String, String> parseAuthHeader(String header) {
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

    private static Fate<String> fetchToken(String authHeader) {
        Map<String, String> parts = parseAuthHeader(authHeader);
        return Fate.of(() -> {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                            .uri(new URI(parts.get("realm") + "?service=" + URLEncoder.encode(parts.get("service"), StandardCharsets.UTF_8) + "&scope=" + URLEncoder.encode(parts.get("scope"), StandardCharsets.UTF_8)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) throw new IOException("Token request failed: " + response.statusCode());
            return Mapper.INSTANCE.readTree(response.body()).get("token").asText();
        });
    }

    private static Fate<HttpResponse<InputStream>> getWithAuth(HttpRequest.Builder request) {
        return Fate.of(() -> {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 401)
                response = http.send(request
                                .header("Authorization", "Bearer " + fetchToken(response.headers().firstValue("www-authenticate").orElseThrow(() -> new IOException("Missing WWW-Authenticate"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) throw new IOException("Request failed: " + response.statusCode());
            return response;
        });
    }

    private static Fate<List<Descriptor>> manifests(String host, String repository, String tag) {
        return getWithAuth(HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + "/v2/" + repository + "/manifests/" + tag))
                .header("Accept", "application/vnd.oci.image.manifest.v1+json").GET())
                .flatMap(response -> {
                    if (response.statusCode() != 200) return new Fate.Err<>(new OCIResolutionException("Manifest request failed: " + response.statusCode()));
                    return new Fate.Ok<>(Mapper.INSTANCE.readValue(response.body(), ManifestList.class).manifests());
                });
    }

    public static Fate<VerifyingInputStream> blob(String host, String repository, Descriptor descriptor) {
        return getWithAuth(HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + "/v2/" + repository + "/blobs/" + descriptor.digest()))
                .GET())
                .flatMap(response -> {
                    if (response.statusCode() != 200) return new Fate.Err<>(new OCIResolutionException("Blob request failed: " + response.statusCode()));
                    return new Fate.Ok<>(new VerifyingInputStream(response.body(), descriptor.digest()));
                });
    }

    public static Fate<Image> resolveImage(URI uri, String arch) {
        var parts = uri.getPath().split(":");
        if (parts.length != 2) return new Fate.Err<>(new OCIResolutionException("Invalid OCI URI format"));

        String repo = parts[0];
        String tag = parts[1];

        return manifests(uri.getHost(), repo, tag)
                .flatMap(descriptors -> Fate.of(descriptors.stream()
                        .filter(descriptor -> descriptor.platform.map(p -> p.architecture.equals(arch)).orElse(false))
                        .findFirst()))
                .flatMap(descriptor -> blob(uri.getHost(), repo, descriptor)
                        .map(stream -> Mapper.INSTANCE.readValue(stream, Manifest.class)))
                .flatMap(manifest -> blob(uri.getHost(), repo, manifest.config())
                        .map(stream -> Mapper.INSTANCE.readValue(stream, ImageConfig.class))
                        .map(config -> new Image(uri, repo, manifest, config)));
    }

    public record Platform(
            String architecture,
            String os
    ) {}

    public record Descriptor(
            String digest,
            int size,
            Optional<Platform> platform
    ) {}

    public record Manifest(
            Descriptor config,
            List<Descriptor> layers
    ) {}

    public record ManifestList(
            List<Descriptor> manifests
    ) {}

    public record Image(
            URI uri,
            String repo,
            Manifest manifest,
            ImageConfig config
    ) {}
}