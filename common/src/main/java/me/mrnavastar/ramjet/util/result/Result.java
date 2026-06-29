package me.mrnavastar.ramjet.util.result;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Result<T, E extends Throwable> permits Err, Ok {

    T unwrap() throws E;

    //<U, E> Result<U, E> ok(Function<S, Result<U, E>> ok);

    <U> Result<U, E> map(Function<T, U> mapper);
    <U> Result<U, E> flatMap(Function<T, Result<U, E>> mapper);

    @FunctionalInterface
    interface ThrowingSupplier<S, F extends Throwable> {
        S get() throws F;
    }

    static <S> Result<S, ?> of(ThrowingSupplier<S, ?> fn) {
        try {
            return new Ok<>(fn.get());
        } catch (Throwable e) {
            return new Err<>(e);
        }
    }
}