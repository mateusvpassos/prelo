package io.github.mateusvpassos.relato;

import io.github.mateusvpassos.relato.model.ReportTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Encontra templates pelo nome ({@code "vendas"} → {@code vendas.report.json}). */
@FunctionalInterface
public interface TemplateLoader {
    String SUFFIX = ".report.json";

    ReportTemplate load(String name);

    /** Templates no classpath, ex.: {@code classpath("reports/")} lê {@code reports/vendas.report.json}. */
    static TemplateLoader classpath(String prefix) {
        return classpath(prefix, null);
    }

    static TemplateLoader classpath(String prefix, ClassLoader loader) {
        String base = normalizePrefix(prefix);
        return name -> {
            String path = base + fileName(name);
            ClassLoader cl = loader != null ? loader : Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = TemplateLoader.class.getClassLoader();
            try (InputStream in = cl.getResourceAsStream(path)) {
                if (in == null) throw new ReportException("template não encontrado no classpath: " + path);
                return Templates.parse(in).withName(name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /** Templates numa pasta do disco (útil em desenvolvimento: alterações valem sem recompilar). */
    static TemplateLoader directory(Path dir) {
        return name -> {
            Path file = dir.resolve(fileName(name));
            if (!Files.isRegularFile(file)) throw new ReportException("template não encontrado: " + file.toAbsolutePath());
            try {
                return Templates.parse(Files.readString(file)).withName(name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static String fileName(String name) {
        return name.endsWith(".json") ? name : name + SUFFIX;
    }

    private static String normalizePrefix(String prefix) {
        String p = prefix == null ? "" : prefix.trim();
        if (p.startsWith("classpath:")) p = p.substring("classpath:".length());
        while (p.startsWith("/")) p = p.substring(1);
        if (!p.isEmpty() && !p.endsWith("/")) p += "/";
        return p;
    }
}
