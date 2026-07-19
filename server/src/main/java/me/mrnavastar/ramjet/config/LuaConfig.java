package me.mrnavastar.ramjet.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.mrnavastar.ramjet.util.FateMap;
import me.mrnavastar.ramjet.util.result.Fate;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.luaj.LuaJ;
import party.iroiro.luajava.value.LuaValue;

import java.io.*;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class LuaConfig {

    public static class LuaConfigException extends Exception {
        public LuaConfigException(String filename, String msg) {
            super(String.format("Failed to evaluate lua config: %s: %s", filename, msg));
        }
    }

    private final File file;
    private final ConcurrentHashMap<String, Results> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> globals = new ConcurrentHashMap<>();

    @Getter
    public static class Results {

        private final UUID session = UUID.randomUUID();
        private final LuaValue luaValue;

        public Results(LuaValue v) {
            luaValue = v;
        }

        public Fate<String> getString(String key) {
            try {
                String str = luaValue.get(key).toString();
                if (str.isBlank()) return new Fate.Err<>(new NoSuchFieldException("No value named: " + key));
                return new Fate.Ok<>(str);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(e);
                return new Fate.Err<>(e);
            }
        }

        public Fate<URI> getURI(String key) {
            return getString(key).flatMap(uri -> Fate.of(() -> new URI(uri)));
        }
    }

    public LuaConfig setGlobal(String variable, Object object) {
        globals.put(variable, object);
        return this;
    }

    public Fate<FateMap<Object, Object>> resolve() {
        try(var lua = new LuaJ()) {
            //lua.openLibraries();

            globals.forEach((variable, luaValue) -> {
                lua.pushJavaObject(luaValue);
                lua.setGlobal(variable);
            });

            var values = lua.eval(new BufferedReader(new FileReader(file)).readAllAsString());
            if (values.length == 0) return new Fate.Err<>(new LuaConfigException(file.getName(), "No return values were provided"));

            System.out.println(values[0].toString());
            System.out.println(values[0].get("uuid"));

            return new Fate.Ok<>(new FateMap<>((Map<Object, Object>) values[0].toJavaObject()));

            /*var results = new Results(values[0]);
            sessions.put(results.session.toString(), results);
            return new Fate.Ok<>(results);*/
        } catch (LuaException | IOException e) {
            return new Fate.Err<>(new LuaConfigException(file.getName(), e.getMessage()));
        }
    }

    /*public Fate<Results> resolve(String session) {
        return Optional.ofNullable(sessions.get(session)).orElse(resolve());
    }*/
}
