package io.github.mateusvpassos.relato;

/** Erro de template, de expressão ou de renderização. A mensagem traz o caminho do elemento no JSON. */
public class ReportException extends RuntimeException {
    private final String path;

    public ReportException(String message) {
        this(message, null, null);
    }

    public ReportException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public ReportException(String message, String path, Throwable cause) {
        super(path == null || path.isEmpty() ? message : path + ": " + message, cause);
        this.path = path;
    }

    /** Caminho do elemento que falhou, ex.: {@code body[2].columns[1].value}; pode ser nulo. */
    public String path() {
        return path;
    }
}
