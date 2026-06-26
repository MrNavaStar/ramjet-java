package me.mrnavastar.ramjet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.mrnavastar.ramjet.util.result.Err;
import me.mrnavastar.ramjet.util.result.Fate;
import me.mrnavastar.ramjet.util.result.Ok;
import me.mrnavastar.ramjet.util.result.Result;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.lua55.Lua55;
import party.iroiro.luajava.value.LuaValue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class LuaConfig {

    public static class LuaConfigException extends Exception {
        public LuaConfigException(String msg) {
            super("Failed to evaluate lua config: " + msg);
        }
    }

    private final String code;
    private final ConcurrentHashMap<String, Results> sessions = new ConcurrentHashMap<>();

    @RequiredArgsConstructor
    public static class Results {
        @Getter
        private final UUID session = UUID.randomUUID();
        private final LuaValue[] results;

        public Optional<String> getString(String key) {
            try {
                String str = results[0].get(key).toString();
                if (str.isBlank()) return Optional.empty();
                return Optional.of(str);
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    public Fate<Results> resolve() {
        try(var lua = new Lua55()) {
            lua.openLibraries();
            var results = new Results(lua.eval(code));
            sessions.put(results.session.toString(), results);
            return new Fate.Ok<>(results);
        } catch (LuaException e) {
            return new Fate.Fail<>(new LuaConfigException(e.getMessage()));
        }
    }

    public Results resolve(String session) {
        return Optional.ofNullable(sessions.get(session)).orElse(resolve());
    }
}
