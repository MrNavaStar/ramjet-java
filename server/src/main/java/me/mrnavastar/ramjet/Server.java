package me.mrnavastar.ramjet;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import me.mrnavastar.ramjet.config.Env;
import me.mrnavastar.ramjet.config.GitConfig;
import me.mrnavastar.ramjet.config.LuaConfig;
import me.mrnavastar.ramjet.util.FateMap;
import me.mrnavastar.ramjet.util.result.Fate;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Server {

    public static final String APP_URL = Env.get("APP_URL");
    public static final String GIT_REPO = Env.get("GIT_REPO");
    public static final String GIT_BRANCH = Env.get("GIT_BRANCH", "main");
    public static final int GIT_POLL_RATE = Integer.parseInt(Env.get("GIT_POLL_RATE", "60"));
    public static final String MACHINE_DIR = Env.get("MACHINE_DIR", "machines");
    public static final String PROFILE_DIR = Env.get("PROFILE_DIR", "profiles");

    private static final FateMap<String, LuaConfig.Results> machines = new FateMap<>(new ConcurrentHashMap<>());
    private static final FateMap<String, LuaConfig> profiles = new FateMap<>(new ConcurrentHashMap<>());

    public static Fate<String> idle(String uuid, Map<String, String> meta) {
        return machines.get(uuid)
                .flatMap(machine -> machine.getString("profile")
                .flatMap(profiles::get)
                .flatMap(config -> config.setGlobal("machine", machine.getLuaValue()).resolve())
                .flatMap(results -> results.getURI("image")
                .flatMap(imageUri -> results.getURI("kernel")
                .flatMap(kernelUri -> OCI.Image.New(imageUri).map(image -> iPXE.boot(image, kernelUri, results.getSession(), APP_URL))))
                ).orElse(iPXE.idle(APP_URL, false, 10)));
    }

    public static String getQueryParam(Context ctx, String param) throws NoSuchElementException {
        return Optional.ofNullable(ctx.queryParam(param)).orElseThrow(() -> new NoSuchElementException(String.format("Request missing '%s' parameter", param)));
    }

    public static void main(String[] args) {
        GitConfig.pollRepo(GIT_REPO, GIT_BRANCH, GIT_POLL_RATE, () -> {
            machines.clear();
            profiles.clear();

            GitConfig.listScripts(MACHINE_DIR)
                    .forEach(filename -> new LuaConfig(filename)
                            .resolve()
                            .flatMap(results -> {
                                results.getString("uuid").map(uuid -> machines.put(uuid, results));
                                return null;
                            }));

            GitConfig.listScripts(PROFILE_DIR)
                    .forEach(filename -> profiles.put(filename.replace(".lua", ""), new LuaConfig(filename)));
        });

        Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.concurrency.useVirtualThreads = true;
            config.staticFiles.add(stat -> {
                stat.hostedPath = "/v1/static";
                stat.directory = "/static";
                stat.location = Location.EXTERNAL;
            });

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
                ctx.result(idle(ctx.pathParam("uuid"), params).resolve());
            });

            config.routes.get("/v1/{repo}/blobs/{digest}", ctx -> {
                ctx.contentType("application/gzip");
                Fate.attempt(() -> OCI.blob(
                    getQueryParam(ctx, "host"),
                    ctx.pathParam("repo"),
                    ctx.pathParam("digest")
                )
                .map(GZIPInputStream::new).map(TarArchiveInputStream::new)
                .flatMap(new ConversionContext(new GZIPOutputStream(ctx.res().getOutputStream()))::tarToCpio))
                .resolve();
            });

            config.routes.get("/v1/fetch", ctx -> {
               IOUtils.copy(new URI(getQueryParam(ctx, "uri")).toURL(), ctx.res().getOutputStream());
            });

        }).start(11722);
    }
}