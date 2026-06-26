package me.mrnavastar.ramjet.util.result;

import java.util.function.Function;

public sealed interface Fate<T> permits Fate.Ok, Fate.Fail {

    @FunctionalInterface
    interface ThrowableFunction<T, R, E extends Exception> {
        R apply(T t) throws E;
    }

    @FunctionalInterface
    interface ThrowableSupplier<T, E extends Exception> {
        T get() throws E;
    }

    @FunctionalInterface
    interface ThrowableRunnable<E extends Exception> {
        void run() throws E;
    }

    T resolve() throws Exception;

    <U> Fate<U> map(ThrowableFunction<T, U, ?> mapper);
    <U> Fate<U> flatMap(Function<T, Fate<U>> mapper);

    static <T> Fate<T> of(ThrowableSupplier<T, ?> fn) {
        try {
            return new Ok<>(fn.get());
        } catch (Exception e) {
            return new Fail<>(e);
        }
    }

    static <T> Fate<T> attempt(ThrowableRunnable<?> fn) {
        try {
            fn.run();
            return new Ok<>(null);
        } catch (Exception e) {
            return new Fail<>(e);
        }
    }

    record Fail<T>(Exception error) implements Fate<T> {

        @Override
        public T resolve() throws Exception {
            throw error;
        }

        @Override
        public <U> Fate<U> map(ThrowableFunction<T, U, ?> mapper) {
            return new Fail<>(error);
        }

        @Override
        public <U> Fate<U> flatMap(Function<T, Fate<U>> mapper) {
            return new Fail<>(error);
        }
    }

    record Ok<T>(T value) implements Fate<T> {
        @Override
        public T resolve() {
            return value;
        }

        @Override
        public <U> Fate<U> map(ThrowableFunction<T, U, ?> mapper) {
            try {
                return new Ok<>(mapper.apply(value));
            } catch (Exception e) {
                return new Fail<>(e);
            }
        }

        @Override
        public <U> Fate<U> flatMap(Function<T, Fate<U>> mapper) {
            return mapper.apply(value);
        }
    }
}
