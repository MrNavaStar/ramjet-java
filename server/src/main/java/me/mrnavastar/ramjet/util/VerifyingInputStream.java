package me.mrnavastar.ramjet.util;

import lombok.Getter;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class VerifyingInputStream extends FilterInputStream {

    @Getter
    private final long size;
    private final MessageDigest digest;
    private final byte[] expectedDigest;
    private boolean closed;
    private long writen = 0;

    public VerifyingInputStream(InputStream in, MessageDigest digest, byte[] expectedDigest, long size) {
        super(in);
        this.digest = digest;
        this.expectedDigest = expectedDigest.clone();
        this.size = size;
    }

    /**
     * Accepts OCI-style digests such as:
     *   sha256:abcd1234...
     *   sha512:abcd1234...
     */
    public VerifyingInputStream(InputStream in, String digestString, long size) throws NoSuchAlgorithmException, DecoderException {
        var digest = digestString.split(":");
        if (digest.length != 2) throw new IllegalArgumentException("Digest must be in the form algorithm:hex");

        this(in,
            MessageDigest.getInstance(
            switch (digest[0].toLowerCase()) {
                case "sha256" -> "SHA-256";
                case "sha512" -> "SHA-512";
                default -> throw new IllegalArgumentException("Unsupported digest algorithm: " + digest[0]);
            }),
            Hex.decodeHex(digest[1]),
            size
        );
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            digest.update((byte) b);
            writen += 1;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            digest.update(b, off, n);
            writen += n;
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        super.close();

        if (writen != size || !Arrays.equals(digest.digest(), expectedDigest))
            throw new IOException("Digest verification failed");
    }
}