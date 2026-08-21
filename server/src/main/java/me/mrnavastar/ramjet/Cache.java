package me.mrnavastar.ramjet;

import io.javalin.http.Context;
import land.oras.ContainerRef;
import land.oras.Registry;
import lombok.Cleanup;
import me.mrnavastar.ramjet.util.result.Fate;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.util.io.TeeOutputStream;

import java.io.*;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

public class Cache {

    private static final Path CACHE_ROOT = Path.of("/cache");

    private static Fate<InputStream> connectUri(URI uri, Context ctx) {
        return Fate.of(() -> {
            URLConnection connection = uri.toURL().openConnection();
            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0) ctx.res().setContentLengthLong(contentLength);
            return connection.getInputStream();
        });
    }

    private static Fate<InputStream> connectBlob(URI uri) {
        Registry registry = Registry.builder().insecure().build();
        ContainerRef ref = ContainerRef.parse(uri.toString().substring(uri.getScheme().length() + 1));

        return Fate.of(() -> registry.getBlobStream(ref))
                .map(GZIPInputStream::new).map(TarArchiveInputStream::new)
                .map(in -> new ConversionContext().tarToCpio(in));
    }

    public static void resolveUri(URI uri, Context ctx) throws Exception {
        System.out.println(uri);

        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8)));
        File file = CACHE_ROOT.resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest).toFile();

        if (file.exists()) {
            connectUri(file.toURI(), ctx).map(in -> IOUtils.copyLarge(in, ctx.res().getOutputStream()));
            return;
        }

        System.out.println("here");

        Fate<InputStream> source = switch (uri.getScheme()) {
            case "http", "https", "file" -> connectUri(uri, ctx);
            case "blob" -> connectBlob(uri);
            default -> throw new IllegalStateException("Unexpected value: " + uri.getScheme());
        };

        System.out.println("here1");

        source.map(in -> {
            File tmp = new File(file.getAbsolutePath() + ".part");
            tmp.getParentFile().mkdirs();

            System.out.println("Here2");

            boolean shouldCache = !uri.getScheme().equals("file");

            @Cleanup OutputStream out = shouldCache ? new TeeOutputStream(new FileOutputStream(tmp), ctx.res().getOutputStream()) : ctx.res().getOutputStream();
            System.out.printf("copied bytes: %d\n", IOUtils.copyLarge(in, out));

            if (shouldCache) Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
            return null;
        });
    }
}