package io.github.mateusvpassos.relato.render;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown mínimo → XHTML, para textos longos (contratos, cartas, certificados). Blocos: parágrafo, # títulos,
 * listas (- * +, 1.), citação (>), linha (---). Em linha: **negrito**, *itálico* / _itálico_, `código`.
 * Uma quebra de linha simples vira {@code <br/>}. O texto de entrada já deve vir escapado para XML.
 */
final class Markdown {
    private Markdown() {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UL = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern OL = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");

    static String toXhtml(String escaped) {
        String[] lines = escaped.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> para = new ArrayList<>();
        String listTag = null;
        for (String raw : lines) {
            String line = raw.stripTrailing();
            Matcher h = HEADING.matcher(line), ul = UL.matcher(line), ol = OL.matcher(line);
            boolean blank = line.isBlank();
            boolean isUl = ul.matches(), isOl = !isUl && ol.matches();
            if (blank || h.matches() || isUl || isOl || quote(line) != null || line.matches("^\\s*(---+|\\*\\*\\*+)\\s*$")) {
                flushPara(out, para);
            }
            if (listTag != null && !(isUl && listTag.equals("ul")) && !(isOl && listTag.equals("ol"))) {
                out.append("</").append(listTag).append(">\n");
                listTag = null;
            }
            if (blank) continue;
            if (h.matches()) {
                int level = h.group(1).length();
                out.append("<h").append(level).append(" class=\"rl-md-h\">").append(inline(h.group(2))).append("</h").append(level).append(">\n");
            } else if (isUl || isOl) {
                String tag = isUl ? "ul" : "ol";
                if (listTag == null) {
                    out.append('<').append(tag).append(">\n");
                    listTag = tag;
                }
                out.append("<li>").append(inline((isUl ? ul : ol).group(1))).append("</li>\n");
            } else if (quote(line) != null) {
                out.append("<blockquote>").append(inline(quote(line))).append("</blockquote>\n");
            } else if (line.matches("^\\s*(---+|\\*\\*\\*+)\\s*$")) {
                out.append("<hr/>\n");
            } else {
                para.add(line);
            }
        }
        flushPara(out, para);
        if (listTag != null) out.append("</").append(listTag).append(">\n");
        return out.toString();
    }

    /** Texto de uma linha de citação ({@code >} cru ou já escapado), ou null. */
    private static String quote(String line) {
        String l = line.stripLeading();
        if (l.startsWith("&gt;")) return l.substring(4).trim();
        if (l.startsWith(">")) return l.substring(1).trim();
        return null;
    }

    private static void flushPara(StringBuilder out, List<String> para) {
        if (para.isEmpty()) return;
        out.append("<p>");
        for (int i = 0; i < para.size(); i++) {
            if (i > 0) out.append("<br/>");
            out.append(inline(para.get(i).strip()));
        }
        out.append("</p>\n");
        para.clear();
    }

    static String inline(String s) {
        String r = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        r = r.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        r = r.replaceAll("__(.+?)__", "<b>$1</b>");
        r = r.replaceAll("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])", "<i>$1</i>");
        r = r.replaceAll("(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)", "<i>$1</i>");
        return r;
    }
}
