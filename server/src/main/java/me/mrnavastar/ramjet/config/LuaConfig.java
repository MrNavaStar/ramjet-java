package me.mrnavastar.ramjet.config;

import lombok.RequiredArgsConstructor;
import me.mrnavastar.ramjet.util.FateMap;
import me.mrnavastar.ramjet.util.result.Fate;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.luaj.LuaJ;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class LuaConfig {

    public static class LuaConfigException extends Exception {
        public LuaConfigException(String filename, String msg) {
            super(String.format("Failed to evaluate lua config: %s: %s", filename, msg));
        }
    }

    private final File file;
    private final ConcurrentHashMap<String, Object> globals = new ConcurrentHashMap<>();

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

            return new Fate.Ok<>(new FateMap<>((Map<Object, Object>) values[0].toJavaObject()));
        } catch (LuaException | IOException e) {
            return new Fate.Err<>(new LuaConfigException(file.getName(), e.getMessage()));
        }
    }
}