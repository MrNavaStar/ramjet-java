package me.mrnavastar.ramjet.config;

import java.util.Optional;

public class Env {

    public static String get(String variable, String ... defaults) {
        return Optional.ofNullable(System.getenv(variable)).orElseGet(() -> {
            if (defaults.length != 0) return defaults[0];
            System.out.printf("Env Variable %s not set, terminating%n", variable);
            System.exit(1);
            return null;
        });
    }
}
