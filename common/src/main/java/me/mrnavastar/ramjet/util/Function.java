package me.mrnavastar.ramjet.util;

public class Function {

    public interface ConsumerWithError<T> {
        void accept(T val) throws Exception;
    }

    public interface BiConsumerWithError<T, B> {
        void accept(T val, B val2) throws Exception;
    }
}
