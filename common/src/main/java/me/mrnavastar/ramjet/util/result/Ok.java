package me.mrnavastar.ramjet.util.result;

import java.util.function.Function;

public record Ok<T, E extends Throwable>(T value) implements Result<T, E> {
    @Override
    public T unwrap() {
        return value;
    }

    @Override
    public <U> Result<U, E> map(Function<T, U> mapper) {
        return new Ok<>(mapper.apply(value));
    }

    @Override
    public <U> Result<U, E> flatMap(Function<T, Result<U, E>> mapper) {
        return mapper.apply(value);
    }
}