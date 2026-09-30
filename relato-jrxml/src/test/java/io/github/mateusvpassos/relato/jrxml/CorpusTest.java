package io.github.mateusvpassos.relato.jrxml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mateusvpassos.relato.Relato;
import io.github.mateusvpassos.relato.SampleData;
import io.github.mateusvpassos.relato.TemplateLoader;
import io.github.mateusvpassos.relato.Templates;
import io.github.mateusvpassos.relato.model.ReportTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Migração em lote de .jrxml reais (-Drelato.jrxml.corpus=pasta1;pasta2). Converte, valida o template e gera
 * o PDF com dados de exemplo. Imprime um resumo e grava os resultados em build/migrados.
 */
class CorpusTest {
    @Test
    void migraCorpusReal() throws Exception {
        String corpus = System.getProperty("relato.jrxml.corpus", "");
        Assumptions.assumeFalse(corpus.isBlank(), "sem corpus");
        List<Path> files = new ArrayList<>();
        for (String dir : corpus.split(";")) {
            try (Stream<Path> s = Files.walk(Path.of(dir))) {
                s.filter(p -> p.toString().endsWith(".jrxml") && !p.toString().contains("target")).forEach(files::add);
            }
        }
        Path out = Path.of("build", "migrados");
        Files.createDirectories(out);
        int converted = 0, parsed = 0, rendered = 0, exprs = 0, exprsFailed = 0, els = 0;
        Map<String, Integer> layouts = new TreeMap<>();
        Map<String, Integer> warningKinds = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        Map<String, String> renderErrors = new LinkedHashMap<>();
        Map<Path, String> outFiles = new LinkedHashMap<>();
        Map<Path, JrxmlConverter.Options> optsByProject = new java.util.HashMap<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Path f : files) known.add(f.getFileName().toString().replace(".jrxml", ""));
        int brokenSources = 0;
        for (Path f : files) {
            String name = f.getFileName().toString().replace(".jrxml", "");
            Path sub = out.resolve(f.getParent().getFileName().toString() + "-" + Math.abs(f.getParent().toString().hashCode() % 1000));
            Files.createDirectories(sub);
            try {
                Path project = projectRoot(f);
                JrxmlConverter.Options opts = optsByProject.computeIfAbsent(project,
                        p -> new JrxmlConverter.Options(SubreportHints.scan(p.resolve("src")), known));
                JrxmlConverter.Result r = JrxmlConverter.convert(Files.readAllBytes(f), name, opts);
                converted++;
                exprs += r.stats().expressions();
                exprsFailed += r.stats().expressionsFailed();
                els += r.stats().elements();
                layouts.merge(r.stats().layout(), 1, Integer::sum);
                for (String w : r.warnings()) warningKinds.merge(kind(w), 1, Integer::sum);
                Path target = sub.resolve(name + ".report.json");
                Files.writeString(target, r.json());
                outFiles.put(target, name);
            } catch (RuntimeException e) {
                if (String.valueOf(e.getMessage()).startsWith("XML inválido")) brokenSources++;
                else failures.add(f + ": " + e);
            }
        }
        System.out.println("arquivos .jrxml quebrados na origem (XML inválido): " + brokenSources);
        // liga sub-relatórios por pasta de saída (como a CLI faz)
        Map<Path, Map<String, String>> byDir = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : outFiles.entrySet()) {
            byDir.computeIfAbsent(e.getKey().getParent(), k -> new LinkedHashMap<>()).put(e.getValue(), Files.readString(e.getKey()));
        }
        for (Map.Entry<Path, Map<String, String>> d : byDir.entrySet()) {
            for (Map.Entry<String, String> e : JrxmlLinker.link(d.getValue()).entrySet()) {
                Files.writeString(d.getKey().resolve(e.getKey() + ".report.json"), e.getValue());
            }
        }
        for (Map.Entry<Path, String> e : outFiles.entrySet()) {
            ReportTemplate t;
            try {
                t = Templates.parse(Files.readString(e.getKey()));
                parsed++;
            } catch (RuntimeException ex) {
                failures.add(e.getKey() + " (template inválido): " + ex.getMessage());
                continue;
            }
            try {
                Relato relato = Relato.builder().templates(TemplateLoader.directory(e.getKey().getParent())).cache(true).build();
                SampleData s = SampleData.generate(t, 8);
                byte[] pdf = relato.report(t).data(s.data()).params(s.params()).pdf();
                rendered++;
                if (System.getProperty("relato.jrxml.png", "").contains(e.getValue())) savePng(pdf, out.resolve(e.getValue() + ".png"));
            } catch (RuntimeException ex) {
                renderErrors.put(e.getValue(), String.valueOf(ex.getMessage()).replaceAll("\\s+", " "));
            }
        }
        System.out.println("=== MIGRAÇÃO: " + files.size() + " arquivos");
        System.out.printf("convertidos %d | template válido %d | PDF gerado com dados de exemplo %d%n", converted, parsed, rendered);
        System.out.printf("expressões %d, não convertidas %d (%.1f%%) | elementos %d%n", exprs, exprsFailed, 100.0 * exprsFailed / Math.max(1, exprs), els);
        System.out.println("layouts: " + layouts);
        System.out.println("avisos por tipo:");
        warningKinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30)
                .forEach(w -> System.out.printf("  %4d  %s%n", w.getValue(), w.getKey()));
        System.out.println("falhas de conversão: " + failures.size());
        failures.stream().limit(20).forEach(f -> System.out.println("  " + f));
        System.out.println("erros ao gerar PDF: " + renderErrors.size());
        Map<String, Integer> errKinds = new TreeMap<>();
        renderErrors.values().forEach(v -> errKinds.merge(kind(v), 1, Integer::sum));
        errKinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(20)
                .forEach(w -> System.out.printf("  %4d  %s%n", w.getValue(), w.getKey()));
        renderErrors.entrySet().stream().limit(40).forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue()));

        assertEquals(List.of(), failures);
        assertTrue(parsed == converted);
        assertTrue(rendered >= converted * 0.9, "gerou PDF de " + rendered + "/" + converted);
    }

    private static void savePng(byte[] pdf, Path png) {
        try (var d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(d).renderImageWithDPI(0, 80), "png", png.toFile());
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static Path projectRoot(Path f) {
        for (Path p = f.getParent(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src")) && (Files.exists(p.resolve("pom.xml")) || Files.exists(p.resolve("build.gradle")))) return p;
        }
        return f.getParent();
    }

    /** Agrupa mensagens parecidas: tira nomes e números. */
    private static String kind(String msg) {
        String s = msg.replaceAll("\\([^)]*\\)", "").replaceAll("'[^']*'", "X").replaceAll("\\$\\w\\{[^}]*\\}", "\\$X").replaceAll("\\d+", "N");
        int colon = s.indexOf(": ");
        if (colon > 0 && colon < 60) s = s.substring(0, colon);
        return s.length() > 90 ? s.substring(0, 90) : s;
    }
}
