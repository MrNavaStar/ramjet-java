package me.mrnavastar.ramjet;

import lombok.experimental.UtilityClass;
import me.mrnavastar.ramjet.util.Result;
import me.mrnavastar.ramjet.util.iPXEBuilder;
import org.apache.hc.core5.net.URIBuilder;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.List;
import java.util.UUID;

import me.mrnavastar.ramjet.util.Result.Ok;
import me.mrnavastar.ramjet.util.Result.Err;

@UtilityClass
public final class iPXE {

    public static Result<String, URISyntaxException> idle(String url, boolean registered, int query_delay) {
        try {
            return new Ok<>(iPXEBuilder.Start()
                    .EchoMultiline("""
                            
                               ___  ___   __  ___ _     __
                              / _ \\/ _ | /  |/  /(_)__ / /_
                             / , _/ __ |/ /|_/ // / -_) __/
                            /_/|_/_/ |_/_/  /_// /\\__/\\__/
                                            |___/
                            
                            """)
                    .Set("mgmt_status", "CONNECTING")
                    .Echo("Flight Deck:", "${mgmt_status}")
                    .Echo("Registered:", registered ? "YES" : "NO")
                    .Echo("Task:", "NONE")
                    .If(query_delay != 0, script -> script.Sleep(query_delay))
                    .Chain(new URIBuilder(url)
                            .setPath("/api/v1/idle")
                            .addParameter("uuid", "${uuid}")
                            .addParameter("mac", "${mac}")
                            .addParameter("serial", "${serial}")
                            .addParameter("asset", "${asset}")
                            .addParameter("hostname", "${hostname}")
                            .addParameter("manufacturer", "${manufacturer}")
                            .addParameter("platform", "${platform}")
                            .addParameter("arch", "${arc}")
                            .addParameter("memsize", "${memsize}")
                            .addParameter("cpuvendor", "${cpuvendor}")
                            .addParameter("cpumodel", "${cpumodel}")
                            .addParameter("version", "${version}")
                            .build(), true)
                    .Set("mgmt_status", "OFFLINE")
                    .Goto("start")
                    .End());
        } catch (URISyntaxException e) {
            return new Err<>(e);
        }
    }

    public static Result<String, URISyntaxException> boot(List<OCI.Descriptor> layers, UUID session, String url, String workingDir, String entrypoint, String cmd, String ports) {
        try {
            return new Ok<>(iPXEBuilder.Start()
                .ForEach(layers, (script, layer) -> {
                    script.Initrd(new URIBuilder(url)
                            .setPath("/api/v1/layers/" + layer.digest())
                            .addParameter("uuid", "${uuid}")
                            .addParameter("session", session.toString())
                            .build());
                })
                .Initrd(new URIBuilder(url)
                    .setPath("/api/v1/initrd")
                    .addParameter("uuid", "${uuid}")
                    .addParameter("session", session.toString())
                    .build())
                .Initrd(new URIBuilder(url)
                    .setPath("/api/v1/inlet")
                    .addParameter("uuid", "${uuid}")
                    .addParameter("session", session.toString())
                    .build(), "/bin/inlet", "mode=755")
                .Chain(new URIBuilder(url)
                    .setPath("/api/v1/kernel")
                    .addParameter("uuid", "${uuid}")
                    .addParameter("session", session.toString())
                    .build(), true,
                    "init=/bin/inlet",
                    "ramjet.mgmt=" + url,
                    "ramjet.working_dir=" + workingDir,
                    "ramjet.entrypoint=" + entrypoint,
                    "ramjet.cmd=" + cmd,
                    "ramjet.ports=" + ports)
                .End());
        } catch (URISyntaxException e) {
            return new Err<>(e);
        }
    }
}