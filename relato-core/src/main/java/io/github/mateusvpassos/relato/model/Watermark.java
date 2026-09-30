package io.github.mateusvpassos.relato.model;

/**
 * Marca d'água repetida em todas as páginas.
 *
 * @param text    texto (aceita expressões, ex.: {@code ${cancelado ? 'CANCELADO' : ''}})
 * @param src     imagem em vez de texto (mesmas formas do bloco image)
 * @param color   cor do texto (padrão cinza claro)
 * @param size    tamanho da fonte em pt (padrão 72)
 * @param angle   rotação em graus (padrão -35)
 * @param opacity 0..1 (padrão 0.15)
 * @param visible expressão; some quando falsa
 */
public record Watermark(String text, String src, String color, Number size, Number angle, Number opacity, String width, String visible) {}
