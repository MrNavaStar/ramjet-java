package me.mrnavastar.ramjet;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.zip.GZIPInputStream;

public class Files {

    private void serveOCI(URI uri) throws Exception {
        var output = new ByteArrayOutputStream();
        var ctx = new ConversionContext(output);

        for (OCI.Layer layer : new OCI.Image(uri).layers()) {
            Thread.ofVirtual().uncaughtExceptionHandler().start(() -> {
                try {
                    ctx.tarToCpio(layer.read());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
