package me.mrnavastar.ramjet;

import io.javalin.http.Context;
import lombok.Cleanup;
import me.mrnavastar.ramjet.url.TarToCpioURLConnection;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public class Cache {

    public static void resolveUri(URI uri, Context ctx) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8)));
        File file = Path.of("/cache").resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest).toFile();

        if (file.exists()) {
            @Cleanup InputStream in = new FileInputStream(file);
            ctx.res().setContentLengthLong(file.length());
            IOUtils.copyLarge(in, ctx.res().getOutputStream());
            return;
        }

        @Cleanup InputStream in = switch (uri.getScheme()) {
                case "http", "https", "file" -> {
                    URLConnection connection = uri.toURL().openConnection();
                    long contentLength = connection.getContentLengthLong();
                    if (contentLength >= 0) ctx.res().setContentLengthLong(contentLength);
                    yield connection.getInputStream();
                }
                case "tarToCpio" -> new TarToCpioURLConnection(uri).getInputStream();

            default -> throw new IllegalStateException("Unexpected value: " + uri.getScheme());
        };

        File tmp = new File(file.getAbsolutePath() + ".part");
        tmp.getParentFile().mkdirs();

        @Cleanup OutputStream cacheOut = new FileOutputStream(tmp);
        OutputStream out = ctx.res().getOutputStream();

        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
            cacheOut.write(buffer, 0, len);
        }

        tmp.renameTo(file);
    }
}