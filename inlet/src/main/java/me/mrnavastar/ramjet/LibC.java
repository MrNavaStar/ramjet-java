package me.mrnavastar.ramjet;

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

class LibC {
    static final int WNOHANG = 1;

    @CFunction("waitpid")
    static native int waitpid(int pid, int status, int options);

    @CFunction("mount")
    static native int mount(CCharPointer src, CCharPointer target, CCharPointer fstype, long flags, CCharPointer data);

    static int mount(String src, String target, String fstype, long flags, String data) {
        try (
                var srcPtr = CTypeConversion.toCString(src);
                var targetPtr = CTypeConversion.toCString(target);
                var fstypePtr = CTypeConversion.toCString(fstype);
                var dataPtr = CTypeConversion.toCString(data)
        ) {
            return mount(srcPtr.get(), targetPtr.get(), fstypePtr.get(), flags, dataPtr.get());
        }
    }
}