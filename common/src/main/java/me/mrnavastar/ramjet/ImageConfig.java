package me.mrnavastar.ramjet;

import java.util.List;
import java.util.Optional;

public record ImageConfig(
            String architecture,
            String os,
            Optional<Config> config


) {
    public record Config(
            Optional<String> user,
            Optional<List<String>> env,
            Optional<List<String>> entrypoint,
            Optional<List<String>> cmd,
            Optional<String> workingDir
    ) {}
}