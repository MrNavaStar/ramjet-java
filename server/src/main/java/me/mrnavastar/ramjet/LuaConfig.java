package me.mrnavastar.ramjet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import party.iroiro.luajava.lua55.Lua55;
import party.iroiro.luajava.value.LuaValue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class LuaConfig {
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
            } catch (Exception _) {
                return Optional.empty();
            }
        }
    }

    public Results resolve() {
        try(var lua = new Lua55()) {
            lua.openLibraries();
            var results = new Results(lua.eval(code));
            sessions.put(results.session.toString(), results);
            return results;
        }
    }

    public Results resolve(String session) {
        return Optional.ofNullable(sessions.get(session)).orElse(resolve());
    }
}
