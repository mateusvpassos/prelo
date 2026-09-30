package io.github.mateusvpassos.relato.model;

import java.util.List;

/**
 * Campo dos dados. Serve de documentação e para gerar dados de exemplo no preview.
 *
 * @param type   string, text, integer, number, decimal, money, date, datetime, boolean, list, object
 * @param fields subcampos quando {@code type} é list ou object
 * @param sample valor fixo para os dados de exemplo (opcional)
 */
public record FieldDef(String name, String type, String label, List<FieldDef> fields, Object sample) {
    public FieldDef {
        fields = fields == null ? List.of() : fields;
    }
}
