package me.mrnavastar.ramjet;

import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.result.Fate;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Inlet {

    static Fate<ImageConfig> getConfig() {
        if (LibC.mount("proc", "/proc", "proc", 0, null) != 0) {
            return new Fate.Err<>(new IOException("Failed to mount virtual filesystem: /proc"));
        }

        try(var reader =  new FileReader("/proc/cmdline")) {
            return Arrays.stream(reader.readAllAsString().split(" "))
                    .map(s -> s.split("=", 1))
                    .filter(s -> s.length == 2 && s[0].equals("ramjet"))
                    .map(s -> new String(Base64.getDecoder().decode(s[1]), StandardCharsets.UTF_8))
                    .map(json -> Fate.of(() -> Mapper.INSTANCE.readValue(json, ImageConfig.class)))
                    .findAny()
                    .orElse(new Fate.Err<>(new NoSuchFieldException()));
        } catch (Exception e) {
            return new Fate.Err<>(e);
        }
    }

    static void main(String[] args) throws Exception {
        if (ProcessHandle.current().pid() != 1) {
            throw new IllegalStateException("Not PID 1");
        }

        Reaper.install();

        int exit = getConfig().map(config -> config.config().map(c -> {
            var command = new ArrayList<String>();
            c.entrypoint().ifPresent(command::addAll);
            c.cmd().ifPresent(command::addAll);

            var workload = new ProcessBuilder(command).inheritIO();
            c.workingDir().ifPresent(dir -> workload.directory(new File(dir)));
            return Fate.of(workload::start);
        })).resolve().get().resolve().waitFor(); //TODO: this is so gross

        System.exit(exit);
    }
}