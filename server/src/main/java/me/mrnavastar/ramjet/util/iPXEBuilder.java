package me.mrnavastar.ramjet.util;

import java.net.URI;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class iPXEBuilder {

    private final StringJoiner script = new StringJoiner("\n", "#!ipxe\n:start", "");

    public static iPXEBuilder Start() {
        return new iPXEBuilder();
    }

    public iPXEBuilder Tag(String tag) {
        script.add(":" + tag);
        return this;
    }

    public iPXEBuilder Goto(String tag) {
        script.add("goto " + tag);
        return this;
    }

    public iPXEBuilder If(boolean condition, Consumer<iPXEBuilder> then) {
        if (condition) then.accept(this);
        return this;
    }

    public <T> iPXEBuilder ForEach(List<T> items, BiConsumer<iPXEBuilder, T> each) {
        items.forEach(i -> each.accept(this, i));
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
        StringBuilder initrd = new StringBuilder("initrd " + uri);
        for (String arg : args) {
            initrd.append(" ").append(arg);
        }
        script.add(initrd.toString());
        return this;
    }

    public iPXEBuilder Chain(URI uri, boolean replace, String ... args) {
        script.add("chain " + (replace ? "--replace" : "") + uri);
        return this;
    }

    public String End() {
        script.add(":end");
        return script.toString();
    }
}
