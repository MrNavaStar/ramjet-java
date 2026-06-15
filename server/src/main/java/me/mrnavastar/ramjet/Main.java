package me.mrnavastar.ramjet;

import io.javalin.Javalin;
import me.mrnavastar.ramjet.util.Result;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Main {

    private static final ConcurrentHashMap<String, Machine> machines = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LuaConfig> configs = new ConcurrentHashMap<>();

    private static String APP_URL = "";

    public static Result<String, URISyntaxException> idle(String uuid, Map<String, String> meta) {
        return Optional.ofNullable(machines.get(uuid))
                .map(machine -> Optional.ofNullable(configs.get(uuid))
                        .map(LuaConfig::resolve)
                        .map(results -> iPXE.boot(results.getSession(), APP_URL,
                                results.getString("working_dir").orElse(""),
                                results.getString("entrypoint").orElse(""),
                                results.getString("cmd").orElse(""),
                                results.getString("ports").orElse("")
                        )).orElse(iPXE.idle(APP_URL, machine.registered(), 10)))
                .orElseGet(() -> {
                    machines.put(uuid, Machine.from(meta));
                    return iPXE.idle(APP_URL, false, 10);
        });
    }

    public static InputStream initrd(String uuid, String session) {
        Optional.ofNullable(machines.get(uuid)).map(m -> {
            Optional.ofNullable(configs.get(session)).map(config -> {
                var image = config.resolve(session).getString("image");

            });
        });
    }

    public static void main(String[] args) {
        Javalin.create(config -> {
            config.routes.exception(Exception.class, (e, ctx) -> {
                ctx.status(500);
                ctx.result(e.toString());
            });

            // Pixiecore Compat
            config.routes.get("/api/v1/boot", ctx -> {
                ctx.result(String.format("{ \"ipxe_script\": \"%s\" }", iPXE.idle(APP_URL, false, 0).unwrap()));
            });

            config.routes.get("/api/v1/idle", ctx -> {
                Map<String, String> params = ctx.queryParamMap().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, value -> value.getValue().getFirst()));
                Optional.ofNullable(ctx.queryParam("uuid")).map(uuid -> ctx.result(idle(uuid, params)));
            });

            config.routes.get("/api/v1/initrd", ctx -> {
                Optional.ofNullable(ctx.queryParam("uuid")).ifPresent(uuid -> {
                    Optional.ofNullable(ctx.queryParam("session")).ifPresent(session -> {
                        ctx.contentType("application/octet-stream");
                        ctx.result(initrd(uuid, session));
                    });
                });
            });

        }).start(11722);
    }
}