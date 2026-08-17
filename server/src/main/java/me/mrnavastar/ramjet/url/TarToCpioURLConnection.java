package me.mrnavastar.ramjet.url;

import lombok.RequiredArgsConstructor;
import me.mrnavastar.ramjet.ConversionContext;
import me.mrnavastar.ramjet.oci.OCI;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.zip.GZIPInputStream;

@RequiredArgsConstructor
public class TarToCpioURLConnection {

    private final URI uri;

    public InputStream getInputStream() throws Exception {

        String[] parts = uri.getPath().replace("/v2/", "").split("/blobs/");
        if (parts.length != 2) {
            throw new IOException("Invalid OCI url");
        }

        return OCI.blob(uri.getHost(), parts[0], new OCI.Descriptor(parts[1], 0, null))
                        .map(GZIPInputStream::new).map(TarArchiveInputStream::new)
                        .map(in -> new ConversionContext().tarToCpio(in)).resolve();
    }
}
