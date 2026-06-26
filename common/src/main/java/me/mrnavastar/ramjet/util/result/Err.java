package me.mrnavastar.ramjet.util.result;

import java.util.function.Function;

public record Err<T, E extends Throwable>(E error) implements Result<T, E> {

    @Override
    public T unwrap() throws E {
        throw error;
    }

    @Override
    public <U> Result<U, E> map(Function<T, U> mapper) {
        return new Err<>(error);
    }

    @Override
    public <U> Result<U, E> flatMap(Function<T, Result<U, E>> mapper) {
        return new Err<>(error);
    }
}