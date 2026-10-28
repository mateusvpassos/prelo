package io.github.mateusvpassos.prelo.expr;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Escopo de avaliação. Cada nível pode ter uma linha corrente (propriedades acessíveis pelo nome),
 * variáveis e a coleção usada pelas agregações ({@code sum(valor)}, {@code count()}...).
 */
public final class Scope {
    private final Scope parent;
    private final Object row;
    private final Map<String, ?> vars;
    private final List<?> items;
    private final Formatter formatter;

    private Scope(Scope parent, Object row, Map<String, ?> vars, List<?> items, Formatter formatter) {
        this.parent = parent;
        this.row = row;
        this.vars = vars;
        this.items = items;
        this.formatter = formatter;
    }

    public static Scope root(Formatter formatter, Map<String, ?> vars) {
        return new Scope(null, null, vars, null, formatter);
    }

    public Scope child(Object row, Map<String, ?> vars, List<?> items) {
        return new Scope(this, row, vars, items, formatter);
    }

    public Scope withRow(Object row) {
        return new Scope(this, row, null, null, formatter);
    }

    public Scope withItems(List<?> items) {
        return new Scope(this, null, null, items, formatter);
    }

    public Formatter formatter() {
        return formatter;
    }

    /** Resolve um identificador: linha corrente, depois variáveis, subindo pelos escopos pais. */
    public Object lookup(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.row != null && Values.hasProperty(s.row, name)) return Values.property(s.row, name);
            if (s.vars != null && s.vars.containsKey(name)) return s.vars.get(name);
        }
        if ("page".equals(name)) return PageRef.PAGE;
        if ("pages".equals(name)) return PageRef.PAGES;
        throw new ExprException("'" + name + "' não encontrado" + hint());
    }

    /** Como {@link #lookup}, mas devolve null em vez de erro. */
    public Object lookupOrNull(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.row != null && Values.hasProperty(s.row, name)) return Values.property(s.row, name);
            if (s.vars != null && s.vars.containsKey(name)) return s.vars.get(name);
        }
        return null;
    }

    /** Linha corrente mais próxima (item da tabela/lista ou o objeto de dados); null se não há. */
    public Object currentRow() {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.row != null) return s.row;
            if (s.vars != null && s.vars.containsKey("rows") && s.vars.containsKey("data")) return s.vars.get("data");
        }
        return null;
    }

    /** Coleção das agregações mais próxima (grupo, tabela, lista ou o conjunto de dados). */
    public List<?> aggregateItems() {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.items != null) return s.items;
        }
        return null;
    }

    private String hint() {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.row != null) {
                Collection<String> names = Values.propertyNames(s.row);
                if (!names.isEmpty()) return " (disponíveis na linha: " + String.join(", ", names) + ")";
            }
        }
        return "";
    }
}
