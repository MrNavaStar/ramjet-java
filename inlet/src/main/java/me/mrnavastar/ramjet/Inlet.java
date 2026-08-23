package me.mrnavastar.ramjet;

import lombok.extern.java.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Log
public class Inlet {

    private static boolean isDebugMode() {
        return System.getenv("ramjet_debug") != null;
    }

    private static void debugShell() throws IOException, InterruptedException {
        if (!new File("/busybox").exists()) return;

        log.warning("Failed to start image! Dropping you into a debug shell so you can poke around :)");

        new File("/debug/bin").mkdirs();
        Files.move(Path.of("/busybox"), Path.of("/debug/busybox"));

        new ProcessBuilder("/debug/busybox", "--install", "-s", "/debug/bin").inheritIO().start().waitFor();
        var shell = new ProcessBuilder("/debug/busybox", "sh").inheritIO();
        shell.environment().put("PATH", "/debug/bin");
        shell.start().waitFor();
    }

    static void main(String[] args) throws Exception {
        if (ProcessHandle.current().pid() != 1) {
            throw new IllegalStateException("Not PID 1");
        }

        Reaper.install();

        try {
            var command = new ArrayList<String>();

            Optional.ofNullable(System.getenv("ramjet_entrypoint")).ifPresent(command::add);
            Optional.ofNullable(System.getenv("ramjet_cmd")).ifPresent(command::add);

            var workload = new ProcessBuilder(command).inheritIO();
            Optional.ofNullable(System.getenv("ramjet_workingdir")).ifPresent(dir -> workload.directory(new File(dir)));

            workload.start().waitFor();
        } catch (Exception e) {
            e.printStackTrace();

            if (isDebugMode()) debugShell();
        }

        System.exit(1);
    }
}