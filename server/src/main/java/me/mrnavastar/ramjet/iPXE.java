package me.mrnavastar.ramjet;

import land.oras.ContainerRef;
import land.oras.Manifest;
import lombok.experimental.UtilityClass;
import me.mrnavastar.ramjet.util.iPXEBuilder;
import me.mrnavastar.ramjet.util.result.Fate;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

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

    public static Fate<String> boot(ContainerRef image, Manifest manifest, URI kernel, String url) {
        AtomicInteger layerIndex = new AtomicInteger();
        return iPXEBuilder.create(script -> script
                .Clear()
                .Kernel(new URIBuilder(url)
                        .setPath("/v1/fetch")
                        .addParameter("uri", kernel.toString())
                        .build(),
                        "initrd=initrd",
                        "root=/dev/ram0",
                        "rdinit=/inlet",
                        "console=ttyAMA0 console=ttyS0",
                        "ramjet_debug=true")
                .Initrd(new URIBuilder("/v1/fetch")
                        .setPath("/v1/fetch")
                        .setParameter("uri", "blob://" + image.withDigest(manifest.getConfig().getDigest()))
                        .build(), "/embedded/config.json", "mode=600")
                .ForEach(manifest.getLayers(), layer ->
                        script.Initrd(new URIBuilder(url)
                        .setPath("/v1/fetch")
                        .addParameter("uri", "blob://" + image.withDigest(layer.getDigest()))
                        .build(), String.format("/embedded/layers/%02d-%s.tar", layerIndex.getAndIncrement(), layer.getDigest()), "mode=600"))
                .Initrd(new URIBuilder(url)
                    .setPath("/v1/fetch")
                    .setParameter("uri", "file:///static/inlet")
                    .build(), "/inlet", "mode=700")
                .Initrd(new URIBuilder(url)
                    .setPath("/v1/fetch")
                    .setParameter("uri", "file:///static/busybox")
                    .build(), "/busybox", "mode=700")
                .Boot()
            );
    }
}