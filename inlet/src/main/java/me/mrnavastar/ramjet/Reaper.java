package me.mrnavastar.ramjet;

import sun.misc.Signal;

final class Reaper {

    static void install() {
        Signal.handle(new Signal("CHLD"), sig -> reap());
    }

    private static void reap() {
        while (true) if (LibC.waitpid(-1, 0, LibC.WNOHANG) <= 0) break;
    }
}