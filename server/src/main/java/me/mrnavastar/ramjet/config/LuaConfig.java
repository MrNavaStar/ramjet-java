package me.mrnavastar.ramjet.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.mrnavastar.ramjet.util.result.Fate;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.lua55.Lua55;
import party.iroiro.luajava.value.LuaValue;

import java.io.*;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class LuaConfig {

    public static class LuaConfigException extends Exception {
        public LuaConfigException(String filename, String msg) {
            super(String.format("Failed to evaluate lua config: %s: %s", filename, msg));
        }
    }

    private final String filename;
    private final ConcurrentHashMap<String, Results> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LuaValue> globals = new ConcurrentHashMap<>();

    @Getter
    @RequiredArgsConstructor
    public static class Results {

        private final UUID session = UUID.randomUUID();
        private final LuaValue luaValue;

        public Fate<String> getString(String key) {
            try {
                String str = luaValue.get(key).toString();
                if (str.isBlank()) return new Fate.Err<>(new NoSuchFieldException("No value named: " + key));
                return new Fate.Ok<>(str);
            } catch (Exception e) {
                return new Fate.Err<>(e);
            }
        }

        public Fate<URI> getURI(String key) {
            return getString(key).flatMap(uri -> Fate.of(() -> new URI(uri)));
        }
    }

    public LuaConfig setGlobal(String variable, LuaValue luaValue) {
        globals.put(variable, luaValue);
        return this;
    }

    public Fate<Results> resolve() {
        try(var lua = new Lua55()) {
            lua.openLibraries();

            globals.forEach((variable, luaValue) -> {
                lua.push(luaValue);
                lua.setGlobal(variable);
            });

            var values = lua.eval(new BufferedReader(new FileReader(filename)).readAllAsString());
            if (values.length == 0) return new Fate.Err<>(new LuaConfigException(filename, "No return values were provided"));

            var results = new Results(values[0]);
            sessions.put(results.session.toString(), results);
            return new Fate.Ok<>(results);
        } catch (LuaException | IOException e) {
            return new Fate.Err<>(new LuaConfigException(filename, e.getMessage()));
        }
    }

    /*public Fate<Results> resolve(String session) {
        return Optional.ofNullable(sessions.get(session)).orElse(resolve());
    }*/
}
