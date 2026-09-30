package io.github.mateusvpassos.relato.model;

/**
 * @param size        A4, A3, A5, Letter, Legal ou medidas ("210mm 297mm")
 * @param orientation portrait | landscape
 * @param margin      margens em CSS ("20mm 15mm"); cabeçalho e rodapé de página ficam dentro das margens superior e inferior
 */
public record Page(String size, String orientation, String margin) {
    public static final Page DEFAULT = new Page(null, null, null);

    public Page {
        size = size == null || size.isBlank() ? "A4" : size;
        orientation = orientation == null || orientation.isBlank() ? "portrait" : orientation;
        margin = margin == null || margin.isBlank() ? "20mm 15mm" : margin;
    }
}
