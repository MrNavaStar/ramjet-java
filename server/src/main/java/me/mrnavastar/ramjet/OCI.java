package me.mrnavastar.ramjet;

import java.io.IOException;
import java.util.List;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import lombok.Getter;
import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.VerifyingInputStream;
import me.mrnavastar.ramjet.util.result.Fate;

public class OCI {

    private static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private static Manifest manifest(String host, String repository, String tag) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("https://" + host + "/v2/" + repository + "/manifests/" + tag))
                        .header("Accept", "application/vnd.oci.image.manifest.v1+json").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("Manifest request failed: " + response.statusCode());
        return Mapper.INSTANCE.readValue(response.body(), Manifest.class);
    }

    public static Fate<VerifyingInputStream> blob(String host, String repository, String digest) {
        return Fate.of(() -> {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create("https://" + host + "/v2/" + repository + "/blobs/" + digest))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
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