package io.github.mateusvpassos.relato.expr;

import io.github.mateusvpassos.relato.ReportException;

public class ExprException extends ReportException {
    public ExprException(String message) {
        super(message);
    }

    public ExprException(String message, Throwable cause) {
        super(message, cause);
    }
}
