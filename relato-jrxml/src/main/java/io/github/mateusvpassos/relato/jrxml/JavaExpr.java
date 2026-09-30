package io.github.mateusvpassos.relato.jrxml;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Traduz expressões Java do JasperReports para expressões do relato. Cobre o que aparece na prática:
 * {@code $F/$P/$V}, concatenação, ternário, comparações, getters, {@code SimpleDateFormat/DecimalFormat},
 * {@code toUpperCase/trim/equals/substring...}, {@code String.valueOf}, {@code new Boolean(...)}, casts.
 * O que não souber traduzir lança {@link Unsupported}.
 */
final class JavaExpr {

    static final class Unsupported extends RuntimeException {
        Unsupported(String msg) {
            super(msg);
        }
    }

    /** Resultado: um literal de texto, um trecho de código, ou uma concatenação (vira template). */
    sealed interface J permits Str, Code, Concat, Fmt {}

    record Str(String value) implements J {}

    record Code(String code) implements J {}

    record Concat(List<J> parts) implements J {}

    /** {@code new SimpleDateFormat("p")} / {@code new DecimalFormat("p")}: só vale seguido de {@code .format(x)}. */
    record Fmt(String pattern) implements J {}

    /** Como traduzir {@code $V{nome}} (definido pelo conversor, que conhece as variáveis do relatório). */
    interface Vars extends Function<String, String> {}

    private final String src;
    private int pos;
    private final Vars vars;

    private JavaExpr(String src, Vars vars) {
        this.src = src;
        this.vars = vars;
    }

    static J parse(String java, Vars vars) {
        JavaExpr p = new JavaExpr(java == null ? "" : java.trim(), vars);
        if (p.src.isEmpty()) return new Str("");
        J j = p.ternary();
        p.ws();
        if (p.pos < p.src.length()) throw new Unsupported("sobrou: " + p.src.substring(p.pos));
        return j;
    }

    /** Código de expressão do relato (sem ${}). */
    static String code(J j) {
        if (j instanceof Str s) return quote(s.value());
        if (j instanceof Code c) return c.code();
        if (j instanceof Fmt) throw new Unsupported("formatador sem .format()");
        List<String> ps = new ArrayList<>();
        for (J p : ((Concat) j).parts()) ps.add(p instanceof Concat ? "(" + code(p) + ")" : code(p));
        return String.join(" + ", ps);
    }

    /** Texto com ${...}: literais viram texto, o resto vira expressão. */
    static String template(J j) {
        if (j instanceof Str s) return s.value().replace("${", "$${");
        if (j instanceof Concat c) {
            StringBuilder sb = new StringBuilder();
            for (J p : c.parts()) sb.append(p instanceof Str s ? s.value().replace("${", "$${") : "${" + code(p) + "}");
            return sb.toString();
        }
        return "${" + code(j) + "}";
    }

    static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    // ------------------------------------------------------------ gramática

    private J ternary() {
        J c = or();
        ws();
        if (peek('?')) {
            pos++;
            J a = ternary();
            expect(':');
            J b = ternary();
            return new Code(paren(c) + " ? " + paren(a) + " : " + paren(b));
        }
        return c;
    }

    private J or() {
        J l = and();
        while (true) {
            ws();
            if (eat("||")) l = new Code(paren(l) + " || " + paren(and()));
            else return l;
        }
    }

    private J and() {
        J l = equality();
        while (true) {
            ws();
            if (eat("&&")) l = new Code(paren(l) + " && " + paren(equality()));
            else return l;
        }
    }

    private J equality() {
        J l = relational();
        while (true) {
            ws();
            if (eat("==")) l = new Code(paren(l) + " == " + paren(relational()));
            else if (eat("!=")) l = new Code(paren(l) + " != " + paren(relational()));
            else return l;
        }
    }

    private J relational() {
        J l = additive();
        while (true) {
            ws();
            String op = eat("<=") ? "<=" : eat(">=") ? ">=" : (peek('<') && !peekStr("<<")) ? take("<") : (peek('>') && !peekStr(">>")) ? take(">") : null;
            if (op == null) {
                if (eatWord("instanceof")) throw new Unsupported("instanceof");
                return l;
            }
            l = new Code(paren(l) + " " + op + " " + paren(additive()));
        }
    }

    private J additive() {
        J l = multiplicative();
        while (true) {
            ws();
            if (peek('+') && !peekStr("++")) {
                pos++;
                J r = multiplicative();
                l = concat(l, r);
            } else if (peek('-') && !peekStr("--")) {
                pos++;
                l = new Code(paren(l) + " - " + paren(multiplicative()));
            } else return l;
        }
    }

    /** '+' com algum texto é concatenação (vira template); senão, soma. */
    private J concat(J l, J r) {
        boolean text = l instanceof Str || r instanceof Str || l instanceof Concat || r instanceof Concat;
        if (!text) return new Code(paren(l) + " + " + paren(r));
        List<J> parts = new ArrayList<>();
        for (J j : List.of(l, r)) {
            if (j instanceof Concat c) parts.addAll(c.parts());
            else parts.add(j);
        }
        // junta literais vizinhos
        List<J> merged = new ArrayList<>();
        for (J j : parts) {
            if (j instanceof Str s && !merged.isEmpty() && merged.get(merged.size() - 1) instanceof Str prev) {
                merged.set(merged.size() - 1, new Str(prev.value() + s.value()));
            } else merged.add(j);
        }
        return new Concat(merged);
    }

    private J multiplicative() {
        J l = unary();
        while (true) {
            ws();
            if (peek('*')) {
                pos++;
                l = new Code(paren(l) + " * " + paren(unary()));
            } else if (peek('/')) {
                pos++;
                l = new Code(paren(l) + " / " + paren(unary()));
            } else if (peek('%')) {
                pos++;
                l = new Code(paren(l) + " % " + paren(unary()));
            } else return l;
        }
    }

    private J unary() {
        ws();
        if (peek('!') && !peekStr("!=")) {
            pos++;
            return new Code("!" + paren(unary()));
        }
        if (peek('-') && !peekStr("--")) {
            pos++;
            return new Code("-" + paren(unary()));
        }
        // cast: (Tipo) expr — ignorado
        int save = pos;
        if (peek('(')) {
            pos++;
            ws();
            String type = qualifiedName();
            ws();
            if (type != null && peek(')') && Character.isUpperCase(type.charAt(type.lastIndexOf('.') + 1))) {
                pos++;
                ws();
                if (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || "$(\"'".indexOf(src.charAt(pos)) >= 0)) return unary();
            }
            pos = save;
        }
        return postfix(primary());
    }

    private J postfix(J target) {
        while (true) {
            ws();
            if (peek('.')) {
                pos++;
                ws();
                String name = ident();
                ws();
                if (peek('(')) {
                    List<J> args = args();
                    target = method(target, name, args);
                } else {
                    target = new Code(paren(target) + "." + name);
                }
            } else if (peek('[')) {
                pos++;
                J k = ternary();
                expect(']');
                target = new Code(paren(target) + "[" + code(k) + "]");
            } else {
                return target;
            }
        }
    }

    private J method(J t, String name, List<J> a) {
        if (t instanceof Fmt f) {
            if (name.equals("format") && a.size() == 1) return new Code("format(" + code(a.get(0)) + ", " + quote(f.pattern()) + ")");
            throw new Unsupported("formatador." + name + "()");
        }
        String tc = paren(t);
        switch (name) {
            case "toUpperCase": return call("upper", t);
            case "toLowerCase": return call("lower", t);
            case "trim": return call("trim", t);
            case "toString": return call("text", t);
            case "length":
            case "size": return call("len", t);
            case "isEmpty": return call("isEmpty", t);
            case "toArray":
            case "iterator":
            case "booleanValue":
            case "intValue":
            case "longValue":
            case "doubleValue":
            case "floatValue":
            case "shortValue": return t;
            case "equals": return new Code(tc + " == " + paren(a.get(0)));
            case "add": return new Code(tc + " + " + paren(a.get(0)));
            case "subtract": return new Code(tc + " - " + paren(a.get(0)));
            case "multiply": return new Code(tc + " * " + paren(a.get(0)));
            case "divide": return new Code(tc + " / " + paren(a.get(0)));
            case "negate": return new Code("-" + tc);
            case "abs": return call("abs", t);
            case "values": return call("values", t);
            case "keySet": return call("keys", t);
            case "equalsIgnoreCase": return new Code("upper(" + code(t) + ") == upper(" + code(a.get(0)) + ")");
            case "substring": return new Code("substr(" + code(t) + ", " + join(a) + ")");
            case "replace":
            case "replaceAll": return new Code("replace(" + code(t) + ", " + join(a) + ")");
            case "contains": return new Code("contains(" + code(t) + ", " + code(a.get(0)) + ")");
            case "startsWith": return new Code("substr(" + code(t) + ", 0, len(" + code(a.get(0)) + ")) == " + paren(a.get(0)));
            case "concat": return concat(t, a.get(0));
            case "get":
                if (a.size() == 1) return new Code(tc + "[" + code(a.get(0)) + "]");
                break;
            default:
                break;
        }
        if (a.isEmpty() && name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
            return new Code(tc + "." + decap(name.substring(3)));
        }
        if (a.isEmpty() && name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
            return new Code(tc + "." + decap(name.substring(2)));
        }
        throw new Unsupported("método ." + name + "()");
    }

    private J primary() {
        ws();
        if (pos >= src.length()) throw new Unsupported("expressão incompleta");
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            J e = ternary();
            expect(')');
            return e instanceof Code ? new Code(paren(e)) : e;
        }
        if (c == '"') return new Str(string('"'));
        if (c == '\'') return new Str(string('\''));
        if (Character.isDigit(c)) return new Code(number());
        if (c == '$' && pos + 2 < src.length() && src.charAt(pos + 2) == '{') {
            char kind = src.charAt(pos + 1);
            int end = src.indexOf('}', pos);
            if (end < 0) throw new Unsupported("$" + kind + "{ sem }");
            String name = src.substring(pos + 3, end);
            pos = end + 1;
            return switch (kind) {
                case 'F' -> new Code(safeName(name));
                case 'P' -> new Code("param" + (isIdent(name) ? "." + name : "['" + name + "']"));
                case 'V' -> {
                    String v = vars.apply(name);
                    if (v == null) throw new Unsupported("variável $V{" + name + "}");
                    yield new Code(v);
                }
                case 'R' -> new Str(name);
                default -> throw new Unsupported("$" + kind + "{}");
            };
        }
        if (eatWord("new")) {
            ws();
            String type = qualifiedName();
            if (type == null) throw new Unsupported("new sem tipo");
            ws();
            if (peek('[')) throw new Unsupported("array");
            List<J> a = args();
            String simple = type.substring(type.lastIndexOf('.') + 1);
            switch (simple) {
                case "SimpleDateFormat":
                case "DecimalFormat":
                    if (a.size() >= 1 && a.get(0) instanceof Str s) return new Fmt(s.value());
                    throw new Unsupported("new " + simple + " sem padrão fixo");
                case "Boolean":
                case "String":
                    return a.isEmpty() ? new Str("") : a.get(0);
                case "Integer":
                case "Long":
                case "Double":
                case "Float":
                case "Short":
                case "BigDecimal":
                case "BigInteger":
                    return a.get(0) instanceof Str ? new Code("number(" + code(a.get(0)) + ")") : a.get(0);
                case "Date":
                case "Timestamp":
                    if (a.isEmpty()) return new Code("now()");
                    throw new Unsupported("new Date(...)");
                default:
                    if (type.contains("JRBeanCollectionDataSource") || type.contains("JRBeanArrayDataSource")) {
                        return a.get(0);
                    }
                    if (type.contains("JREmptyDataSource")) return new Code("null");
                    throw new Unsupported("new " + simple + "(...)");
            }
        }
        String name = qualifiedName();
        if (name == null) throw new Unsupported("caractere inesperado '" + c + "'");
        ws();
        switch (name) {
            case "true", "Boolean.TRUE", "java.lang.Boolean.TRUE": return new Code("true");
            case "false", "Boolean.FALSE", "java.lang.Boolean.FALSE": return new Code("false");
            case "null": return new Code("null");
            default: break;
        }
        if (peek('(')) {
            List<J> a = args();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            String owner = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
            String ownerSimple = owner.substring(owner.lastIndexOf('.') + 1);
            if (simple.equals("equals") && (owner.endsWith("Boolean.TRUE") || owner.endsWith("Boolean.FALSE"))) {
                return new Code((owner.endsWith("TRUE") ? "true" : "false") + " == " + paren(a.get(0)));
            }
            if (simple.equals("valueOf") && (ownerSimple.equals("String"))) return call("text", a.get(0));
            if (simple.equals("valueOf") && ownerSimple.equals("Boolean")) return a.get(0);
            if ((simple.equals("valueOf") || simple.startsWith("parse")) && !ownerSimple.isEmpty()) return new Code("number(" + code(a.get(0)) + ")");
            if (ownerSimple.equals("Math") && (simple.equals("abs") || simple.equals("round"))) return call(simple, a.get(0));
            throw new Unsupported("chamada " + name + "()");
        }
        if (name.contains(".")) throw new Unsupported("constante " + name);
        return new Code(safeName(name));
    }

    // ------------------------------------------------------------ utilitários

    private static J call(String fn, J arg) {
        return new Code(fn + "(" + code(arg) + ")");
    }

    private static String join(List<J> a) {
        List<String> out = new ArrayList<>();
        for (J j : a) out.add(code(j));
        return String.join(", ", out);
    }

    /** Parênteses quando o trecho tem operadores (evita mudar a precedência). */
    private static String paren(J j) {
        String c = code(j);
        if (j instanceof Str || c.matches("[\\w.$\\[\\]'()]*") && !c.contains(" ")) return c;
        if (c.startsWith("(") && c.endsWith(")") && balanced(c.substring(1, c.length() - 1))) return c;
        return "(" + c + ")";
    }

    private static boolean balanced(String s) {
        int d = 0;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                if (c == '\\') i++;
                else if (c == q) q = 0;
            } else if (c == '\'' || c == '"') q = c;
            else if (c == '(') d++;
            else if (c == ')' && --d < 0) return false;
        }
        return d == 0;
    }

    // campos da linha têm precedência sobre variáveis (page, data...), então só palavras-chave precisam de escape
    private static final java.util.Set<String> RESERVED = java.util.Set.of("true", "false", "null", "and", "or", "not");

    private static String safeName(String name) {
        if (isIdent(name) && !RESERVED.contains(name)) return name;
        // campo "a.b.c" do JRBeanDataSource = navegação no bean
        if (name.matches("[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)+") && !RESERVED.contains(name.substring(0, name.indexOf('.')))) return name;
        return "data['" + name + "']";
    }

    private static boolean isIdent(String s) {
        return s.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static String decap(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private List<J> args() {
        expect('(');
        List<J> out = new ArrayList<>();
        ws();
        if (peek(')')) {
            pos++;
            return out;
        }
        do {
            out.add(ternary());
            ws();
        } while (eat(","));
        expect(')');
        return out;
    }

    private String string(char q) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == q) return sb.toString();
            if (c == '\\' && pos < src.length()) {
                char n = src.charAt(pos++);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        throw new Unsupported("texto sem fechar aspas");
    }

    private String number() {
        int start = pos;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
        String n = src.substring(start, pos);
        if (pos < src.length() && "LlDdFf".indexOf(src.charAt(pos)) >= 0) pos++;
        return n;
    }

    private String qualifiedName() {
        if (pos >= src.length() || !Character.isJavaIdentifierStart(src.charAt(pos)) || src.charAt(pos) == '$') return null;
        StringBuilder sb = new StringBuilder(ident());
        while (pos + 1 < src.length() && src.charAt(pos) == '.' && Character.isJavaIdentifierStart(src.charAt(pos + 1))) {
            // pára antes de chamada de método de instância em minúscula depois de um nome de classe? mantém simples:
            int save = pos;
            pos++;
            String next = ident();
            // "obj.getX()" deve ser tratado como método: só junta se o que vem não é seguido de '(' OU se ainda é pacote/classe
            int after = pos;
            while (after < src.length() && src.charAt(after) == ' ') after++;
            boolean call = after < src.length() && src.charAt(after) == '(';
            boolean looksType = Character.isUpperCase(sb.charAt(0)) || sb.toString().matches("(java|javax|net|org|com)(\\..*)?");
            if (call && !looksType) {
                pos = save;
                break;
            }
            sb.append('.').append(next);
        }
        return sb.toString();
    }

    private String ident() {
        int start = pos;
        if (pos >= src.length() || !Character.isJavaIdentifierStart(src.charAt(pos))) throw new Unsupported("nome esperado em «" + src + "»");
        pos++;
        while (pos < src.length() && Character.isJavaIdentifierPart(src.charAt(pos)) && src.charAt(pos) != '$') pos++;
        return src.substring(start, pos);
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

    private String take(String s) {
        pos += s.length();
        return s;
    }

    private boolean eat(String s) {
        if (src.startsWith(s, pos)) {
            pos += s.length();
            return true;
        }
        return false;
    }

    private boolean eatWord(String w) {
        if (src.startsWith(w, pos) && (pos + w.length() >= src.length() || !Character.isJavaIdentifierPart(src.charAt(pos + w.length())))) {
            pos += w.length();
            return true;
        }
        return false;
    }

    private void expect(char c) {
        ws();
        if (!peek(c)) throw new Unsupported("esperado '" + c + "' em «" + src + "»");
        pos++;
    }
}
