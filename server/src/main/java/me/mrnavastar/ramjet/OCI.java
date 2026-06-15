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
import lombok.AllArgsConstructor;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class OCI {

    private static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ObjectMapper mapper = new ObjectMapper();

    public record Descriptor(
            String digest
    ) {}

    public record Manifest(
            Descriptor config,
            List<Descriptor> layers
    ) {}

    private static Manifest manifest(String host, String repository, String tag) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + "/v2/" + repository + "/manifests/" + tag))
                .header("Accept", "application/vnd.oci.image.manifest.v1+json").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("Manifest request failed: " + response.statusCode());
        return mapper.readValue(response.body(), Manifest.class);
    }

    private static InputStream blob(String host, String repository, String digest) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + "/v2/" + repository + "/blobs/" + digest))
                .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("Blob request failed: " + response.statusCode());
        return response.body();
    }


    @AllArgsConstructor
    public static class Layer {
        private final String host;
        private final String repo;
        private final String digest;

        public TarArchiveInputStream read() throws IOException, InterruptedException {
            return new TarArchiveInputStream(new GZIPInputStream(blob(host, repo, digest)));
        }
    }

    public static class Image {
        private final URI uri;
        private final String repo;
        private final String tag;

        public Image(URI uri) {
            var parts = uri.getPath().split(":");
            this.uri = uri;
            this.repo = parts[0];
            this.tag = parts[1];
        }

        public List<Layer> layers() throws Exception {
            return manifest(uri.getHost(), repo, tag).layers().stream()
                    .map(layer -> new Layer(uri.getHost(), repo, layer.digest())).toList();
        }
    }
}