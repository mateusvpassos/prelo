package io.github.mateusvpassos.relato;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adaptadores de dados. */
public final class Rows {
    private Rows() {}

    /**
     * Lê o ResultSet inteiro em mapas (chave = label da coluna, em minúsculas). Não fecha o ResultSet.
     * Em relatórios grandes, prefira paginar a consulta.
     */
    public static List<Map<String, Object>> of(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) names[i] = md.getColumnLabel(i + 1).toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(n * 2);
            for (int i = 0; i < n; i++) row.put(names[i], rs.getObject(i + 1));
            out.add(row);
        }
        return out;
    }
}
