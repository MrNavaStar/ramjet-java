package me.mrnavastar.ramjet;

import java.io.IOException;
import java.util.List;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import me.mrnavastar.ramjet.util.VerifyingInputStream;
import me.mrnavastar.ramjet.util.result.Fate;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class OCI {

    private static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static Manifest manifest(String host, String repository, String tag) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("https://" + host + "/v2/" + repository + "/manifests/" + tag))
                        .header("Accept", "application/vnd.oci.image.manifest.v1+json").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("Manifest request failed: " + response.statusCode());
        return mapper.readValue(response.body(), Manifest.class);
    }

    public static Fate<TarArchiveInputStream> layer(String host, String repository, String digest) {
        return Fate.of(() -> {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create("https://" + host + "/v2/" + repository + "/blobs/" + digest))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw new IOException("Layer request failed: " + response.statusCode());
            return new TarArchiveInputStream(new GZIPInputStream(new VerifyingInputStream(response.body(), digest)));
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

        private Image(URI uri) throws IOException, InterruptedException {
            var parts = uri.getPath().split(":");
            this.uri = uri;
            this.repo = parts[0];
            String tag = parts[1];
            manifest = manifest(uri.getHost(), repo, tag);
        }

        public static Fate<Image> New(URI uri) {
            return Fate.of(() -> new Image(uri));
        }
    }
}