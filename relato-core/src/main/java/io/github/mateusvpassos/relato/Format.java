package io.github.mateusvpassos.relato;

public enum Format {
    PDF("application/pdf", "pdf"),
    HTML("text/html; charset=UTF-8", "html"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");

    private final String contentType;
    private final String extension;

    Format(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public static Format of(String s) {
        for (Format f : values()) if (f.extension.equalsIgnoreCase(s) || f.name().equalsIgnoreCase(s)) return f;
        throw new IllegalArgumentException("formato desconhecido: " + s + " (use pdf, html ou xlsx)");
    }
}
