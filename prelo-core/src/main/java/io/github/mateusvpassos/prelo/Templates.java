package io.github.mateusvpassos.prelo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.github.mateusvpassos.prelo.model.ReportTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Leitura de {@code *.report.json}. */
public final class Templates {
    private Templates() {}

    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA);

    public static ReportTemplate parse(String json) {
        try {
            return MAPPER.readValue(json, ReportTemplate.class);
        } catch (UnrecognizedPropertyException e) {
            throw new ReportException("propriedade desconhecida '" + e.getPropertyName() + "' em " + location(e)
                    + " (válidas: " + e.getKnownPropertyIds() + ")", e);
        } catch (InvalidTypeIdException e) {
            throw new ReportException("tipo de elemento inválido '" + e.getTypeId() + "' em " + location(e)
                    + " (válidos: text, html, image, row, box, table, list, fields, line, spacer, pageBreak)", e);
        } catch (JsonProcessingException e) {
            throw new ReportException("JSON inválido: " + e.getOriginalMessage() + " em " + location(e), e);
        }
    }

    public static ReportTemplate parse(InputStream in) throws IOException {
        return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String location(JsonProcessingException e) {
        var loc = e.getLocation();
        String where = loc == null ? "" : "linha " + loc.getLineNr() + ", coluna " + loc.getColumnNr();
        if (e instanceof com.fasterxml.jackson.databind.JsonMappingException m && !m.getPath().isEmpty()) {
            StringBuilder p = new StringBuilder();
            for (var r : m.getPath()) {
                if (r.getFieldName() != null) p.append(p.length() == 0 ? "" : ".").append(r.getFieldName());
                else if (r.getIndex() >= 0) p.append('[').append(r.getIndex()).append(']');
            }
            return p + " (" + where + ")";
        }
        return where;
    }
}
