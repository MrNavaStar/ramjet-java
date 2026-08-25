package me.mrnavastar.ramjet;

import land.oras.ContainerRef;
import land.oras.Manifest;
import land.oras.Registry;
import lombok.experimental.UtilityClass;
import me.mrnavastar.ramjet.util.Mapper;
import me.mrnavastar.ramjet.util.OCI;
import me.mrnavastar.ramjet.util.iPXEBuilder;
import me.mrnavastar.ramjet.util.result.Fate;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

@UtilityClass
public final class iPXE {

    private static Fate<ImageConfig> getConfig(byte[] data) {
        return Fate.of(() -> Mapper.INSTANCE.readValue(new String(data), ImageConfig.class));
    }

    private static String join(List<String> list) {
        StringJoiner joiner = new StringJoiner(" ");
        list.forEach(joiner::add);
        return joiner.toString();
    }

    private static String getConfigArgs(ImageConfig config) {
        StringJoiner args = new StringJoiner(" ");
        config.config().ifPresent(c -> {
            c.cmd().ifPresent(cmd -> args.add("ramjet_cmd=\"" + join(cmd) + "\""));
            c.entrypoint().ifPresent(entrypoint -> args.add("ramjet_entrypoint=\"" + join(entrypoint) + "\""));
            c.workingDir().ifPresent(workingDir -> args.add("ramjet_workingdir=\"" + workingDir + "\""));
        });
        return args.toString();
    }

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

    public static Fate<String> boot(Registry registry, ContainerRef image, Manifest manifest, URI kernel, String url) {
        AtomicInteger layerIndex = new AtomicInteger();
        return getConfig(registry.getBlob(image.withDigest(manifest.getConfig().getDigest())))
            .flatMap(config -> iPXEBuilder.create(script -> script
                .Clear()
                .Kernel(new URIBuilder(url)
                        .setPath("/v1/fetch")
                        .addParameter("uri", kernel.toString())
                        .build(),
                        "initrd=initrd",
                        "root=/dev/ram0",
                        "rdinit=/inlet",
                        "console=ttyAMA0 console=ttyS0",
                        "ramjet_debug=true",
                        getConfigArgs(config))
                .ForEach(manifest.getLayers(), layer ->
                        script.Initrd(new URIBuilder(url)
                        .setPath("/v1/fetch")
                        .addParameter("uri", "blob://" + image.withDigest(layer.getDigest()))
                        .build(), String.format("/layers/%02d-%s%s", layerIndex.getAndIncrement(), layer.getDigest(), OCI.getBlobFileExtension(layer.getMediaType()))))
                .Initrd(new URIBuilder(url)
                    .setPath("/v1/fetch")
                    .setParameter("uri", "file:///static/inlet")
                    .build(), "/inlet", "mode=755")
                .Initrd(new URIBuilder(url)
                    .setPath("/v1/fetch")
                    .setParameter("uri", "file:///static/busybox")
                    .build(), "/busybox", "mode=755")
                .Boot()
            ));
    }
}