package me.mrnavastar.ramjet.util;

import lombok.RequiredArgsConstructor;
import me.mrnavastar.ramjet.util.result.Fate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

@RequiredArgsConstructor
public class FateMap<K, V> {

    private final Map<K, V> backend;

    public int size() {
        return backend.size();
    }

    public boolean isEmpty() {
        return backend.isEmpty();
    }

    public boolean containsKey(K key) {
        return backend.containsKey(key);
    }

    public boolean containsValue(V value) {
        return backend.containsValue(value);
    }

    public Fate<V> get(K key) {
        return Fate.of(() -> {
            var value = backend.get(key);
            if (value == null) throw new NoSuchFieldException("No field named: " + key);
            return value;
        });
    }

    public @Nullable V put(K key, V value) {
        return backend.put(key, value);
    }

    public Fate<V> remove(K key) {
        return Fate.of(() -> {
            var value = backend.remove(key);
            if (value == null) throw new NoSuchFieldException("No field named: " + key);
            return value;
        });
    }

    public void putAll(@NonNull Map<? extends K, ? extends V> m) {
        backend.putAll(m);
    }

    public void clear() {
        backend.clear();
    }

    public @NonNull Set<K> keySet() {
        return backend.keySet();
    }

    public @NonNull Collection<V> values() {
        return backend.values();
    }

    public @NonNull Set<Map.Entry<K, V>> entrySet() {
        return backend.entrySet();
    }
}
