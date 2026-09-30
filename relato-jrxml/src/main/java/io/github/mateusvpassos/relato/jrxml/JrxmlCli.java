package io.github.mateusvpassos.relato.jrxml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Migração em lote: {@code relato-jrxml <entrada: arquivo ou pasta> <pasta de saída>}.
 * Gera um {@code nome.report.json} por {@code .jrxml} e imprime um resumo.
 */
public final class JrxmlCli {
    private JrxmlCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: relato-jrxml <arquivo.jrxml | pasta> <pasta de saída> [pasta do código Java, para achar os sub-relatórios]");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        java.util.Map<String, String> hints = args.length > 2 ? SubreportHints.scan(Path.of(args[2])) : java.util.Map.of();
        if (!hints.isEmpty()) System.out.println(hints.size() + " parâmetro(s) de sub-relatório encontrados no código");
        Files.createDirectories(out);
        List<Path> files;
        try (Stream<Path> s = Files.isDirectory(in) ? Files.walk(in) : Stream.of(in)) {
            files = s.filter(p -> p.toString().toLowerCase().endsWith(".jrxml")).sorted().toList();
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Path f : files) known.add(f.getFileName().toString().replaceFirst("(?i)\\.jrxml$", ""));
        JrxmlConverter.Options opts = new JrxmlConverter.Options(hints, known);
        int ok = 0, withWarnings = 0, failed = 0;
        java.util.Map<String, String> converted = new java.util.LinkedHashMap<>();
        for (Path f : files) {
            String name = f.getFileName().toString().replaceFirst("(?i)\\.jrxml$", "");
            try {
                JrxmlConverter.Result r = JrxmlConverter.convert(Files.readAllBytes(f), name, opts);
                converted.put(name, r.json());
                ok++;
                if (!r.warnings().isEmpty()) withWarnings++;
                System.out.printf("%-60s %s, %d aviso(s)%n", name, r.stats().layout(), r.warnings().size());
            } catch (RuntimeException e) {
                failed++;
                System.out.printf("%-60s FALHOU: %s%n", name, e.getMessage());
            }
        }
        // segunda passada: campos dos sub-relatórios viram subcampos das listas no pai
        for (java.util.Map.Entry<String, String> e : JrxmlLinker.link(converted).entrySet()) {
            Files.writeString(out.resolve(e.getKey() + ".report.json"), e.getValue());
        }
        System.out.printf("%n%d convertido(s) (%d com avisos para revisar), %d falha(s)%n", ok, withWarnings, failed);
    }
}
