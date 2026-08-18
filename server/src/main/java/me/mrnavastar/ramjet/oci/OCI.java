package me.mrnavastar.ramjet.oci;

import java.util.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import me.mrnavastar.ramjet.ImageConfig;
import me.mrnavastar.ramjet.util.Http;
import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.VerifyingInputStream;
import me.mrnavastar.ramjet.util.result.Fate;

public class OCI {

    public static class OCIResolutionException extends Exception {
        public OCIResolutionException(String msg) {
            super(String.format("Failed to resolve OCI object: %s", msg));
        }
    }

    private static Fate<List<Descriptor>> manifests(String host, String repo, String tag) {
        return Fate.of(() -> Http.INSTANCE.send(HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + "/v2/" + repo + "/manifests/" + tag))
                .header("Authorization", "Bearer " + Auth.getToken(host, repo).get())
                .header("Accept", "application/vnd.oci.image.manifest.v1+json")
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream()))
                .flatMap(response -> {
                    if (response.statusCode() != 200)
                        return new Fate.Err<>(new OCIResolutionException("Manifest request failed: " + response.statusCode()));
                    return new Fate.Ok<>(Mapper.INSTANCE.readValue(response.body(), ManifestList.class).manifests());
                });
    }

    public static Fate<VerifyingInputStream> blob(String host, String repo, Descriptor descriptor) {
        return Fate.of(() -> Http.INSTANCE.send(HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + "/v2/" + repo + "/blobs/" + descriptor.digest()))
                .header("Authorization", "Bearer " + Auth.getToken(host, repo).get())
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream()))
                .flatMap(response -> {
                    if (response.statusCode() != 200) return new Fate.Err<>(new OCIResolutionException("Blob request failed: " + response.statusCode()));

                    return Fate.of(response.headers()
                            .firstValue("Content-Length"))
                            .map(size -> new VerifyingInputStream(response.body(), descriptor.digest(), Long.parseLong(size)));
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