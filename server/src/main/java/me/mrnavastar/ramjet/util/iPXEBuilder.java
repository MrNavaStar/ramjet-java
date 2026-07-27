package me.mrnavastar.ramjet.util;

import me.mrnavastar.ramjet.util.result.Fate;

import java.net.URI;
import java.util.List;
import java.util.StringJoiner;

public class iPXEBuilder {

    public static class iPXEBuildException extends Exception {
        public iPXEBuildException(String msg) {
            super("Failed to build iPXE script: " + msg);
        }
    }

    private final StringJoiner script = new StringJoiner("\n", "#!ipxe\n", "");
    private Exception exception;

    public static Fate<String> create(Function.ConsumerWithError<iPXEBuilder> then) {
        iPXEBuilder builder = new iPXEBuilder();
        try {
            then.accept(builder);
        } catch (Exception e) {
            builder.exception = e;
        }

        if (builder.exception != null) return new Fate.Err<>(new iPXEBuildException(builder.exception.getMessage()));
        builder.script.add(":end");
        return new Fate.Ok<>(builder.script.toString());
    }

    public iPXEBuilder If(boolean condition, Runnable block) {
        try {
            if (condition) block.run();
        } catch (Exception e) {
            exception = e;
        }
        return this;
    }

    public <T> iPXEBuilder ForEach(List<T> items, Function.ConsumerWithError<T> block) {
        try {
            for (T item : items) block.accept(item);
        } catch (Exception e) {
            exception = e;
        }
        return this;
    }

    public iPXEBuilder Tag(String tag) {
        script.add(":" + tag);
        return this;
    }

    public iPXEBuilder Goto(String tag) {
        script.add("goto " + tag);
        return this;
    }

    public iPXEBuilder Set(String variable, String value) {
        script.add("set " + variable + " " + value);
        return this;
    }

    public iPXEBuilder Set(String variable, int value) {
        return Set(variable, Integer.toString(value));
    }

    public iPXEBuilder Echo(String ... msg) {
        StringBuilder echo = new StringBuilder("echo");
        for (String line : msg) {
            echo.append(" ").append(line);
        }
        script.add(echo.toString());
        return this;
    }

    public iPXEBuilder EchoMultiline(String msg) {
        for (String line : msg.split("\n")) {
            script.add("echo " + line);
        }
        return this;
    }

    public iPXEBuilder Sleep(int seconds) {
        script.add("sleep " + seconds);
        return this;
    }

    public iPXEBuilder Initrd(URI uri, String ... args) {
        StringBuilder initrd = new StringBuilder("initrd " + uri.toASCIIString().replace("%24%7B", "${").replace("%7D", "}"));
        for (String arg : args) {
            initrd.append(" ").append(arg);
        }
        script.add(initrd.toString());
        return this;
    }

    public iPXEBuilder Chain(URI uri, boolean replace, String ... args) {
        StringBuilder chain = new StringBuilder("chain " + (replace ? "--replace " : "") + uri.toASCIIString().replace("%24%7B", "${").replace("%7D", "}"));
        for (String arg : args) {
            chain.append(" ").append(arg);
        }
        script.add(chain.toString());
        return this;
    }

    public iPXEBuilder Kernel(URI uri, String ... args) {
        StringBuilder kernel = new StringBuilder("kernel " + uri.toASCIIString().replace("%24%7B", "${").replace("%7D", "}"));
        for (String arg : args) {
            kernel.append(" ").append(arg);
        }
        script.add(kernel.toString());
        return this;
    }

    public iPXEBuilder Boot() {
        script.add("boot");
        return this;
    }

    public iPXEBuilder Clear() {
        script.add("console");
        return this;
    }

    public iPXEBuilder SetBackground(URI uri) {
        script.add("console --picture " + uri);
        return this;
    }
}