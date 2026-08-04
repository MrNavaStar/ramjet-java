package me.mrnavastar.ramjet;

import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.result.Fate;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

public class Inlet {

    static Fate<ImageConfig> getConfig() {
        return Fate.of(Optional.ofNullable(System.getenv("ramjet")))
                .map(json -> Base64.getDecoder().decode(json))
                .map(json -> Mapper.INSTANCE.readValue(json, ImageConfig.class));
    }

    static void main(String[] args) throws Exception {
        if (ProcessHandle.current().pid() != 1) {
            throw new IllegalStateException("Not PID 1");
        }

        Reaper.install();

        Path dirrr = Paths.get(args.length > 0 ? args[0] : ".");

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirrr)) {
            for (Path path : stream) {
                PosixFileAttributes attrs = Files.readAttributes(
                        path,
                        PosixFileAttributes.class
                );

                System.out.printf(
                        "%s %s%n",
                        PosixFilePermissions.toString(attrs.permissions()),
                        path.getFileName()
                );
            }
        }


        int exit = getConfig().map(config -> config.config().map(c -> {
            var command = new ArrayList<String>();
            //c.entrypoint().ifPresent(command::addAll);
            //c.cmd().ifPresent(command::addAll);

            //command.set(0, "./docker-entrypoint.sh");

            //System.out.println(command);

            command.add("/bin/bash");

            var workload = new ProcessBuilder(command).inheritIO();
            c.workingDir().ifPresent(dir -> workload.directory(new File(dir)));
            return Fate.of(workload::start);
        })).resolve().get().resolve().waitFor(); //TODO: this is so gross

        System.exit(exit);
    }
}