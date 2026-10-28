package io.github.mateusvpassos.prelo.expr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser das expressões dentro de {@code ${...}}.
 *
 * <pre>
 * ternário  : ou ('?' expr ':' expr)?
 * ou        : e (('||' | 'or' | '??') e)*
 * e         : igual (('&&' | 'and') igual)*
 * igual     : rel (('==' | '!=') rel)*
 * rel       : soma (('<' | '<=' | '>' | '>=') soma)*
 * soma      : mult (('+' | '-') mult)*
 * mult      : unário (('*' | '/' | '%') unário)*
 * unário    : ('!' | 'not' | '-') unário | posfixo
 * posfixo   : primário ('.' nome | '[' expr ']')*
 * primário  : número | 'texto' | "texto" | true | false | null | nome '(' args ')' | nome | '(' expr ')'
 * </pre>
 */
public final class Parser {
    private final String src;
    private int pos;

    private Parser(String src) {
        this.src = src;
    }

    public static Expr parse(String src) {
        Parser p = new Parser(src);
        p.ws();
        if (p.pos >= src.length()) throw new ExprException("expressão vazia");
        Expr e = p.ternary();
        p.ws();
        if (p.pos < src.length()) throw p.error("sobrou '" + src.substring(p.pos) + "'");
        return e;
    }

    private Expr ternary() {
        Expr c = or();
        ws();
        if (peek('?') && !peekStr("??")) {
            pos++;
            Expr a = ternary();
            expect(':');
            Expr b = ternary();
            return new Expr.Conditional(c, a, b);
        }
        return c;
    }

    private Expr or() {
        Expr l = and();
        while (true) {
            ws();
            if (eat("||") || eatWord("or")) l = new Expr.Binary("||", l, and());
            else if (eat("??")) l = new Expr.Binary("??", l, and());
            else return l;
        }
    }

    private Expr and() {
        Expr l = equality();
        while (true) {
            ws();
            if (eat("&&") || eatWord("and")) l = new Expr.Binary("&&", l, equality());
            else return l;
        }
    }

    private Expr equality() {
        Expr l = relational();
        while (true) {
            ws();
            if (eat("==")) l = new Expr.Binary("==", l, relational());
            else if (eat("!=")) l = new Expr.Binary("!=", l, relational());
            else return l;
        }
    }

    private Expr relational() {
        Expr l = additive();
        while (true) {
            ws();
            if (eat("<=")) l = new Expr.Binary("<=", l, additive());
            else if (eat(">=")) l = new Expr.Binary(">=", l, additive());
            else if (eat("<")) l = new Expr.Binary("<", l, additive());
            else if (eat(">")) l = new Expr.Binary(">", l, additive());
            else return l;
        }
    }

    private Expr additive() {
        Expr l = multiplicative();
        while (true) {
            ws();
            if (eat("+")) l = new Expr.Binary("+", l, multiplicative());
            else if (eat("-")) l = new Expr.Binary("-", l, multiplicative());
            else return l;
        }
    }

    private Expr multiplicative() {
        Expr l = unary();
        while (true) {
            ws();
            if (eat("*")) l = new Expr.Binary("*", l, unary());
            else if (eat("/")) l = new Expr.Binary("/", l, unary());
            else if (eat("%")) l = new Expr.Binary("%", l, unary());
            else return l;
        }
    }

    private Expr unary() {
        ws();
        if (peek('!') && !peekStr("!=")) {
            pos++;
            return new Expr.Unary('!', unary());
        }
        if (eatWord("not")) return new Expr.Unary('!', unary());
        if (eat("-")) return new Expr.Unary('-', unary());
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (true) {
            ws();
            if (peek('.') ) {
                pos++;
                ws();
                e = new Expr.Prop(e, ident());
            } else if (eat("?.")) {
                ws();
                e = new Expr.Prop(e, ident());
            } else if (peek('[')) {
                pos++;
                Expr k = ternary();
                expect(']');
                e = new Expr.Index(e, k);
            } else {
                return e;
            }
        }
    }

    private Expr primary() {
        ws();
        if (pos >= src.length()) throw error("expressão incompleta");
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            Expr e = ternary();
            expect(')');
            return e;
        }
        if (c == '\'' || c == '"') return new Expr.Literal(string(c));
        if (Character.isDigit(c)) return new Expr.Literal(number());
        if (isIdentStart(c)) {
            String name = ident();
            switch (name) {
                case "true": return new Expr.Literal(Boolean.TRUE);
                case "false": return new Expr.Literal(Boolean.FALSE);
                case "null": return new Expr.Literal(null);
                default: break;
            }
            ws();
            if (peek('(')) {
                pos++;
                List<Expr> args = new ArrayList<>();
                ws();
                if (!peek(')')) {
                    do {
                        args.add(ternary());
                        ws();
                    } while (eat(","));
                }
                expect(')');
                if (!Functions.exists(name)) throw error("função desconhecida '" + name + "' (existem: " + Functions.names() + ")");
                return new Expr.Call(name, args);
            }
            return new Expr.Ident(name);
        }
        throw error("caractere inesperado '" + c + "'");
    }

    private String string(char quote) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == quote) return sb.toString();
            if (c == '\\' && pos < src.length()) {
                char n = src.charAt(pos++);
                sb.append(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        throw error("texto sem fechar aspas");
    }

    private BigDecimal number() {
        int start = pos;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
        if (pos + 1 < src.length() && src.charAt(pos) == '.' && Character.isDigit(src.charAt(pos + 1))) {
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        return new BigDecimal(src.substring(start, pos).replace("_", ""));
    }

    private String ident() {
        if (pos >= src.length() || !isIdentStart(src.charAt(pos))) throw error("nome esperado");
        int start = pos++;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) pos++;
        return src.substring(start, pos);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private boolean peekStr(String s) {
        return src.startsWith(s, pos);
    }

    private boolean eat(String s) {
        if (src.startsWith(s, pos)) {
            pos += s.length();
            return true;
        }
        return false;
    }

    private boolean eatWord(String w) {
        if (src.startsWith(w, pos) && (pos + w.length() >= src.length() || !isIdentPart(src.charAt(pos + w.length())))) {
            pos += w.length();
            return true;
        }
        return false;
    }

    private void expect(char c) {
        ws();
        if (!peek(c)) throw error("esperado '" + c + "'");
        pos++;
    }

    private ExprException error(String msg) {
        return new ExprException(msg + " na posição " + (pos + 1) + " de «" + src + "»");
    }
}
