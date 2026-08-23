package me.mrnavastar.ramjet;

import io.javalin.Javalin;
import io.javalin.http.Context;
import land.oras.*;
import land.oras.auth.BearerTokenProvider;
import land.oras.utils.Const;
import lombok.extern.slf4j.Slf4j;
import me.mrnavastar.ramjet.config.Env;
import me.mrnavastar.ramjet.config.GitConfig;
import me.mrnavastar.ramjet.config.LuaConfig;
import me.mrnavastar.ramjet.util.FateMap;
import me.mrnavastar.ramjet.util.result.Fate;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class Server {

    public static final String APP_URL = Env.get("APP_URL");
    public static final String GIT_REPO = Env.get("GIT_REPO");
    public static final String GIT_BRANCH = Env.get("GIT_BRANCH", "master");
    public static final int GIT_POLL_RATE = Integer.parseInt(Env.get("GIT_POLL_RATE", "120"));
    public static final String MACHINE_DIR = Env.get("MACHINE_DIR", "machines");
    public static final String PROFILE_DIR = Env.get("PROFILE_DIR", "profiles");

    private static final FateMap<String, FateMap<Object, Object>> machines = new FateMap<>(new ConcurrentHashMap<>());
    private static final FateMap<String, LuaConfig> profiles = new FateMap<>(new ConcurrentHashMap<>());

    public static Fate<String> getBootScript(String uuid, Map<String, String> meta) {
        return machines.get(uuid)
                .flatMap(machine -> machine.get("profile")
                .cast(String.class)
                .flatMap(profiles::get)
                .flatMap(config -> config.setGlobal("machine", machine).resolve())
                .flatMap(results -> results.get("image").cast(String.class)
                .flatMap(imageUri -> results.get("kernel").cast(String.class)
                .flatMap(kernelUri -> machine.get("arch").cast(String.class)
                .flatMap(arch -> {

                    URI uri = URI.create(imageUri);

                    Registry registry = Registry.builder().withAuthProvider(new BearerTokenProvider()).build();

                    ContainerRef image = ContainerRef.parse(uri.toString().substring(uri.getScheme().length() + 3));

                    ManifestDescriptor d = registry.getIndex(image).filter(Platform.of(Const.PLATFORM_LINUX, arch)).getFirst();

                    Manifest manifest = registry.getManifest(image.withDigest(d.getDigest()));

                    return iPXE.boot(registry, image, manifest, URI.create(kernelUri), APP_URL);
                })))));
    }

    public static String getQueryParam(Context ctx, String param) throws NoSuchElementException {
        return Optional.ofNullable(ctx.queryParam(param)).orElseThrow(() -> new NoSuchElementException(String.format("Request missing '%s' parameter", param)));
    }

    static void main(String[] args) {
        GitConfig.pollRepo(GIT_REPO, GIT_BRANCH, GIT_POLL_RATE, () -> {
            machines.clear();
            profiles.clear();

            GitConfig.listScripts(MACHINE_DIR)
                    .forEach(file -> new LuaConfig(file).resolve()
                            .flatMap(results -> results.get("uuid").cast(String.class).map(uuid -> {
                                log.info("Loaded Machine Config: {}", uuid);
                                return machines.put(uuid, results);
                            })));

            GitConfig.listScripts(PROFILE_DIR)
                    .forEach(file -> {
                        var profile = file.getName().replace(".lua", "");
                        profiles.put(profile, new LuaConfig(file));
                        log.info("Loaded Profile: {}", profile);
                    });
        });

        Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.concurrency.useVirtualThreads = true;

            config.routes.exception(Exception.class, (e, ctx) -> {
                ctx.status(500);
                ctx.result(e.toString());
                log.error(e.toString());
                e.printStackTrace();
            });

            // Pixiecore Compat
            config.routes.get("/v1/boot", ctx ->
                    ctx.result(String.format("{ \"ipxe_script\": \"%s\" }", iPXE.idle(APP_URL, false, 0).resolve())));

            config.routes.get("/v1/idle", ctx ->
                    ctx.result(iPXE.idle(APP_URL, false, 0).resolve()));

            config.routes.get("/v1/idle/{uuid}", ctx -> {
                Map<String, String> params = ctx.queryParamMap().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, value -> value.getValue().getFirst()));

                ctx.result(getBootScript(ctx.pathParam("uuid"), params).resolve());
                        //.or(() -> iPXE.idle(APP_URL, false, 10).resolve()).resolve());
            });

            config.routes.get("/v1/fetch", ctx ->
                    Cache.resolveUri(URI.create(getQueryParam(ctx, "uri")), ctx));

        }).start(11722);
    }
}