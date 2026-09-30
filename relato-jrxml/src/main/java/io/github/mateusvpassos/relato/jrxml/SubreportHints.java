package io.github.mateusvpassos.relato.jrxml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Descobre qual sub-relatório vai em cada parâmetro lendo o código Java do sistema, ex.:
 * {@code parametros.put("pathCabecalho", path + "CabecalhoPadraoRetrato.jasper")} → pathCabecalho = CabecalhoPadraoRetrato.
 */
public final class SubreportHints {
    private SubreportHints() {}

    private static final Pattern PUT = Pattern.compile("\\.put\\s*\\(\\s*\"(\\w+)\"\\s*,([^;]{0,300}?)\"([^\"]*?)([\\w.-]+)\\.(?:jasper|jrxml)\"");

    /** Varre os .java (e .jsp/.kt) de uma pasta. Se um parâmetro aparece com vários arquivos, vale o primeiro. */
    public static Map<String, String> scan(Path sourceRoot) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) return out;
        try (Stream<Path> s = Files.walk(sourceRoot)) {
            s.filter(p -> {
                String n = p.toString().toLowerCase();
                return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".jsp");
            }).forEach(p -> {
                String text;
                try {
                    byte[] b = Files.readAllBytes(p);
                    text = JrxmlConverter.decode(b);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (!text.contains(".jasper") && !text.contains(".jrxml")) return;
                Matcher m = PUT.matcher(text);
                while (m.find()) out.putIfAbsent(m.group(1), m.group(4));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
