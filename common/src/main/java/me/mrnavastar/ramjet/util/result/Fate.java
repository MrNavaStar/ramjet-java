package me.mrnavastar.ramjet.util.result;

import java.rmi.NoSuchObjectException;
import java.util.Optional;
import java.util.function.Consumer;

public sealed interface Fate<T> {

    @FunctionalInterface
    interface ThrowableFunction<T, R, E extends Exception> {
        R apply(T t) throws E;
    }

    @FunctionalInterface
    interface ThrowableSupplier<T, E extends Exception> {
        T get() throws E;
    }

    interface ThrowableConsumer<T, E extends Exception> {
        void accept(T val) throws E;
    }

    @FunctionalInterface
    interface ThrowableRunnable<E extends Exception> {
        void run() throws E;
    }

    T resolve() throws Exception;

    boolean isOk();

    boolean isErr();

    <U> Fate<U> map(ThrowableFunction<T, U, ?> mapper);
    <U> Fate<U> flatMap(ThrowableFunction<T, Fate<U>, ?> mapper);

    Fate<T> or(ThrowableSupplier<T, ?> fn);

    T orElse(T value);

    <U> Fate<U> cast(Class<U> clazz);

    Fate<T> peekErr(Consumer<Exception> peeker);

    static <T> Fate<T> of(ThrowableSupplier<T, ?> fn) {
        try {
            return new Ok<>(fn.get());
        } catch (Exception e) {
            return new Err<>(e);
        }
    }

    static <T> Fate<T> of(Optional<T> optional) {
        if (optional.isPresent()) return new Ok<>(optional.get());
        return new Err<>(new NoSuchObjectException("Optional is empty"));
    }

    static <T> Fate<T> attempt(ThrowableRunnable<?> fn) {
        try {
            fn.run();
            return new Ok<>(null);
        } catch (Exception e) {
            return new Err<>(e);
        }
    }

    record Err<T>(Exception error) implements Fate<T> {

        @Override
        public T resolve() throws Exception {
            throw error;
        }

        @Override
        public boolean isOk() {
            return false;
        }

        @Override
        public boolean isErr() {
            return true;
        }

        @Override
        public <U> Fate<U> map(ThrowableFunction<T, U, ?> mapper) {
            return new Err<>(error);
        }

        @Override
        public <U> Fate<U> flatMap(ThrowableFunction<T, Fate<U>, ?> mapper) {
            return new Err<>(error);
        }

        @Override
        public Fate<T> or(ThrowableSupplier<T, ?> fn) {
            return Fate.of(fn);
        }

        @Override
        public T orElse(T value) {
            return value;
        }

        @Override
        public <U> Fate<U> cast(Class<U> clazz) {
            return new Err<>(error);
        }

        @Override
        public Fate<T> peekErr(Consumer<Exception> peeker) {
            peeker.accept(error);
            return this;
        }
    }

    record Ok<T>(T value) implements Fate<T> {
        @Override
        public T resolve() {
            return value;
        }

        @Override
        public boolean isOk() {
            return true;
        }

        @Override
        public boolean isErr() {
            return false;
        }

        @Override
        public <U> Fate<U> map(ThrowableFunction<T, U, ?> mapper) {
            try {
                return new Ok<>(mapper.apply(value));
            } catch (Exception e) {
                return new Err<>(e);
            }
        }

        @Override
        public <U> Fate<U> flatMap(ThrowableFunction<T, Fate<U>, ?> mapper) {
            try {
                return mapper.apply(value);
            } catch (Exception e) {
                return new Err<>(e);
            }
        }

        @Override
        public Fate<T> or(ThrowableSupplier<T, ?> fn) {
            return new Ok<>(value);
        }

        @Override
        public T orElse(T value) {
            return this.value;
        }

        @Override
        public <U> Fate<U> cast(Class<U> clazz) {
            try {
                return new Ok<>(clazz.cast(value));
            } catch (Exception e) {
                return new Err<>(e);
            }
        }

        @Override
        public Fate<T> peekErr(Consumer<Exception> peeker) {
            return this;
        }
    }
}
