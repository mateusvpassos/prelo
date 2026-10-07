package io.github.mateusvpassos.relato.model;

/** Metadados do PDF (aceitam expressões). O título vem de {@code title} do template. */
public record Metadata(String author, String subject, String keywords, String creator) {}
