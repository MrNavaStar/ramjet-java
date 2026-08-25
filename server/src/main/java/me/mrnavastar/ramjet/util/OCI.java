package me.mrnavastar.ramjet.util;

public class OCI {

    public static String getBlobFileExtension(String mediaType) {
        if (mediaType.endsWith("+gzip")) return ".tar.gz";
        if (mediaType.endsWith("+zstd")) return ".tar.zst";
        if (mediaType.equals("application/vnd.oci.image.layer.v1.tar")) return ".tar";
        return "";
    }
}
