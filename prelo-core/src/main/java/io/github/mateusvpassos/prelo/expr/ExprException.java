package io.github.mateusvpassos.prelo.expr;

import io.github.mateusvpassos.prelo.ReportException;

public class ExprException extends ReportException {
    public ExprException(String message) {
        super(message);
    }

    public ExprException(String message, Throwable cause) {
        super(message, cause);
    }
}
