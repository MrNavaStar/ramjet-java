package me.mrnavastar.ramjet;

import io.javalin.http.Context;
import lombok.Cleanup;
import me.mrnavastar.ramjet.url.TarToCpioURLConnection;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.TeeInputStream;

import java.io.*;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

public class Cache {

    private static final Path CACHE_ROOT = Path.of("/cache");

    private static InputStream connectUri(URI uri, Context ctx) throws IOException {
        URLConnection connection = uri.toURL().openConnection();
        long contentLength = connection.getContentLengthLong();
        if (contentLength >= 0) ctx.res().setContentLengthLong(contentLength);
        return connection.getInputStream();
    }

    public static void resolveUri(URI uri, Context ctx) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8)));
        File file = CACHE_ROOT.resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest).toFile();

        if (file.exists()) {
            IOUtils.copyLarge(connectUri(file.toURI(), ctx), ctx.res().getOutputStream());
            return;
        }

        @Cleanup InputStream source = switch (uri.getScheme()) {
            case "http", "https", "file" -> connectUri(uri, ctx);
            case "tarToCpio" -> new TarToCpioURLConnection(uri).getInputStream();
            default -> throw new IllegalStateException("Unexpected value: " + uri.getScheme());
        };

        File tmp = new File(file.getAbsolutePath() + ".part");
        tmp.getParentFile().mkdirs();

        boolean shouldCache = !uri.getScheme().equals("file");
        @Cleanup InputStream in = shouldCache ? new TeeInputStream(source, new FileOutputStream(tmp)) : source;
        IOUtils.copy(in, ctx.res().getOutputStream());

        if (shouldCache) Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        );
    }
}