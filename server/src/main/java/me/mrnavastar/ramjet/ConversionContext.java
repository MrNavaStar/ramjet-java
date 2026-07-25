package me.mrnavastar.ramjet;

import lombok.RequiredArgsConstructor;
import me.mrnavastar.ramjet.util.result.Fate;
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

@RequiredArgsConstructor
public class ConversionContext {

    private final ConcurrentHashMap<String, Integer> inodes = new ConcurrentHashMap<>();
    private int inode = 0;

    private int getInode(String filename) {
        return inodes.computeIfAbsent(filename, _ -> inode++);
    }

    private boolean hasConverted(String filename) {
        return inodes.containsKey(filename);
    }

    private CpioArchiveEntry tarToCpio(TarArchiveEntry tar) {
        var cpio = new CpioArchiveEntry(CpioConstants.FORMAT_NEW);
        cpio.setName(tar.getName());
        cpio.setTime(tar.getLastModifiedTime());
        cpio.setGID(tar.getLongGroupId());
        cpio.setUID(tar.getLongUserId());

        if (tar.isLink()) {
            // Hard Link
        } else {
            cpio.setInode(getInode(tar.getName()));
        }

        if (tar.isDirectory()) {
            cpio.setMode(CpioConstants.C_ISDIR);
        }

        else if (tar.isFile()) {
            cpio.setMode(CpioConstants.C_ISREG);
            cpio.setSize(tar.getSize());
        }

        else if (tar.isSymbolicLink()) {
            cpio.setMode(CpioConstants.C_ISLNK);
            cpio.setSize(tar.getLinkName().length());
        }

        else if (tar.isBlockDevice() || tar.isCharacterDevice()) {
            cpio.setDeviceMin(tar.getDevMinor());
            cpio.setDeviceMaj(tar.getDevMajor());
        }

        return cpio;
    }

/*    public Fate<CpioArchiveOutputStream> tarToCpio(TarArchiveInputStream tar) {
        return Fate.of(() -> {
            CpioArchiveOutputStream cpio = new CpioArchiveOutputStream(out);

            TarArchiveEntry tarEntry;
            while ((tarEntry = tar.getNextEntry()) != null) {
                if (!hasConverted(tarEntry.getName())) {
                    cpio.putArchiveEntry(tarToCpio(tarEntry));
                    tar.transferTo(cpio);
                    cpio.closeArchiveEntry();
                    cpio.flush();
                }
            }
            cpio.finish();
            return cpio;
        });
    }*/

    public InputStream tarToCpio(TarArchiveInputStream tar) {
        try {
            PipedInputStream in = new PipedInputStream();
            PipedOutputStream out = new PipedOutputStream(in);

            Thread.ofVirtual().start(() -> {
                try (CpioArchiveOutputStream cpio = new CpioArchiveOutputStream(out)) {
                    TarArchiveEntry tarEntry;

                    while ((tarEntry = tar.getNextEntry()) != null) {
                        if (!hasConverted(tarEntry.getName())) {
                            cpio.putArchiveEntry(tarToCpio(tarEntry));
                            tar.transferTo(cpio);
                            cpio.closeArchiveEntry();
                        }
                    }

                    cpio.finish();
                } catch (IOException e) {
                    try {
                        out.close();
                    } catch (IOException ignored) {}
                }
            });

            return in;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
