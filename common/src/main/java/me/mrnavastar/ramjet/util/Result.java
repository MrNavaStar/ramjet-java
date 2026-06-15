package me.mrnavastar.ramjet.util;

public sealed interface Result<S, F extends Exception> {

    record Ok<S, F extends Exception>(S value) implements Result<S, F> {
        @Override
        public S unwrap() {
            return value;
        }
    }

    record Err<S, F extends Exception>(F error) implements Result<S, F> {
        @Override
        public S unwrap() throws F {
            throw error;
        }
    }

    S unwrap() throws F;
}
