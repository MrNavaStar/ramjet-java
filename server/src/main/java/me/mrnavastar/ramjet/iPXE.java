package me.mrnavastar.ramjet;

import lombok.experimental.UtilityClass;
import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.result.Result;
import me.mrnavastar.ramjet.util.iPXEBuilder;
import me.mrnavastar.ramjet.util.result.Fate;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@UtilityClass
public final class iPXE {

    public static Fate<String> idle(String url, boolean registered, int query_delay) {
        return iPXEBuilder.create(script -> script
            .SetBackground(new URI("http://boot.ipxe.org/ipxe.png"))
            .Tag("start")
            .EchoMultiline("""
            
               ___  ___   __  ___ _     __
              / _ \\/ _ | /  |/  /(_)__ / /_
             / , _/ __ |/ /|_/ // / -_) __/
            /_/|_/_/ |_/_/  /_// /\\__/\\__/
                            |___/
            
            """)
            .Set("mgmt_status", "CONNECTING")
            .Echo("")
            .Echo("UUID:", "${uuid}")
            .Echo("Flight Deck:", "${mgmt_status}")
            .Echo("Registered:", registered ? "YES" : "NO")
            .Echo("Task:", "NONE")
            .If(query_delay != 0, () -> script.Sleep(query_delay))
            .Chain(new URIBuilder(url)
                    .setPath("/v1/idle/${uuid}")
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
            .Clear()
            .Goto("start")
        );
    }

    public static Fate<String> boot(OCI.Image image, URI kernel, UUID session, String url) {
        return Fate.of(() -> Mapper.INSTANCE.writeValueAsString(image.getConfig()))
            .map(config -> Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8)))
            .flatMap(json -> iPXEBuilder.create(script ->
                script.Set("session", session.toString())
                .Set("image_host", image.getUri().getHost())
                .ForEach(image.getManifest().layers(), layer ->
                        script.Initrd(new URIBuilder(url)
                        .setPath(String.format("/v1/%s/blobs/%s", image.getRepo(), layer.digest()))
                        .addParameter("uuid", "${uuid}")
                        .addParameter("session", "${session}")
                        .addParameter("host", "${image_host}")
                        .build()))
                .Initrd(new URIBuilder(url)
                    .setPath("/v1/static/inlet")
                    .addParameter("uuid", "${uuid}")
                    .addParameter("session", "${session}")
                    .build(), "/bin/inlet", "mode=755")
                .Chain(new URIBuilder(url)
                    .setPath("/v1/fetch")
                    .addParameter("uuid", "${uuid}")
                    .addParameter("session", "${session}")
                    .addParameter("uri", kernel.toString())
                    .build(), true,
                    "init=/bin/inlet",
                    "ramjet=" + json)
            ));
    }
}