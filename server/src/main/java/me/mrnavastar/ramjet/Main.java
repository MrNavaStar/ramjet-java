package me.mrnavastar.ramjet;

import io.javalin.Javalin;
import io.javalin.http.Context;
import me.mrnavastar.ramjet.util.iPXEBuilder;
import me.mrnavastar.ramjet.util.result.Fate;
import me.mrnavastar.ramjet.util.result.Result;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

public class Main {

    private static final ConcurrentHashMap<String, Machine> machines = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LuaConfig> configs = new ConcurrentHashMap<>();

    private static String APP_URL = "";

    public static Fate<String> idle(String uuid, Map<String, String> meta) {
        return Optional.ofNullable(machines.get(uuid))
                .map(machine -> Optional.ofNullable(configs.get(uuid))
                        .map(s -> s.resolve().map(results -> {

                              results.getString("image").ifPresent(uri -> {

                                Fate<String> i = Fate.of(() -> new URI(uri))
                                        .flatMap(OCI.Image::New)
                                        .flatMap(image -> iPXE.boot(image, results.getSession(), APP_URL,
                                                results.getString("working_dir").orElse(""),
                                                results.getString("entrypoint").orElse(""),
                                                results.getString("cmd").orElse(""),
                                                results.getString("ports").orElse("")
                                        ));

                                switch (i) {
                                    case Fate.Ok(var value) -> { }
                                    case Fate.Fail(Throwable error) -> {}
                                }
                            });


                            return null;
                        })).orElse(iPXE.idle(APP_URL, machine.registered(), 10)))
                .orElseGet(() -> {
                    machines.put(uuid, Machine.from(meta));
                    return iPXE.idle(APP_URL, false, 10);
        });
    }

    public static String getQueryParam(Context ctx, String param) throws NoSuchElementException {
        return Optional.ofNullable(ctx.queryParam(param)).orElseThrow(() -> new NoSuchElementException(String.format("Request missing '%s' parameter", param)));
    }

    public static void main(String[] args) {
        Arrays.stream(Objects.requireNonNull(new File("./configs").list()))
                .map(filename -> {
                    try {
                        return new BufferedReader(new FileReader(filename));
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }).forEach(file -> {
                    try {
                        configs.put("", new LuaConfig(file.readAllAsString()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.concurrency.useVirtualThreads = true;

            config.routes.exception(Exception.class, (e, ctx) -> {
                ctx.status(500);
                ctx.result(e.toString());
            });

            // Pixiecore Compat
            config.routes.get("/v1/boot", ctx ->
                    ctx.result(String.format("{ \"ipxe_script\": \"%s\" }", iPXE.idle(APP_URL, false, 0).resolve())));

            config.routes.get("/v1/idle/{uuid}", ctx -> {
                Map<String, String> params = ctx.queryParamMap().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, value -> value.getValue().getFirst()));
                ctx.result(idle(ctx.pathParam("uuid"), params).unwrap());
            });

            config.routes.get("/v1/{repo}/blobs/{digest}", ctx -> {
                ctx.contentType("application/gzip");
                Fate.attempt(() -> OCI.layer(
                    getQueryParam(ctx, "host"),
                    ctx.pathParam("repo"),
                    ctx.pathParam("digest")
                ).flatMap(new ConversionContext(new GZIPOutputStream(ctx.res().getOutputStream()))::tarToCpio))
                .resolve();
            });

        }).start(11722);
    }
}