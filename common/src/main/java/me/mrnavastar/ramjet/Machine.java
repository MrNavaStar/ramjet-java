package me.mrnavastar.ramjet;

import java.util.Map;
import java.util.Optional;

public record Machine(
        boolean registered,
        Meta metadata,
        String script,
        String workingDir,
        String entrypoint,
        String cmd,
        String ports
) {
    public static Machine from(Map<String, String> params) {
        return new Machine(
                false,
                Meta.from(params),
                defaultScript(),
                null,
                null,
                null,
                null
        );
    }

    public record Meta(
            Optional<String> mac,
            Optional<String> serial,
            Optional<String> asset,
            Optional<String> hostname,
            Optional<String> manufacturer,
            Optional<String> platform,
            Optional<String> arch,
            Optional<String> memsize,
            Optional<String> cpuvendor,
            Optional<String> cpumodel,
            Optional<String> version
    ) {
        public static Meta from(Map<String, String> params) {
            return new Meta(
                    Optional.ofNullable(params.get("mac")),
                    Optional.ofNullable(params.get("serial")),
                    Optional.ofNullable(params.get("asset")),
                    Optional.ofNullable(params.get("hostname")),
                    Optional.ofNullable(params.get("manufacturer")),
                    Optional.ofNullable(params.get("platform")),
                    Optional.ofNullable(params.get("arch")),
                    Optional.ofNullable(params.get("memsize")),
                    Optional.ofNullable(params.get("cpuvendor")),
                    Optional.ofNullable(params.get("cpumodel")),
                    Optional.ofNullable(params.get("version"))
            );
        }

        public String summary() {
            return String.format(
                    "%s %s %s",
                    hostname.isEmpty() ? "" : hostname,
                    mac.isEmpty() ? "" : mac,
                    platform.isEmpty() ? "" : platform
            );
        }
    }

    public static String defaultScript() {
        return """
                return {
                    kernel = "",
                    image = "",
                }
                """;
    }
}