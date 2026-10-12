package io.github.mateusvpassos.relato.server;

import io.github.mateusvpassos.relato.Format;
import io.github.mateusvpassos.relato.Relato;
import io.github.mateusvpassos.relato.ReportException;
import io.github.mateusvpassos.relato.SampleData;
import io.github.mateusvpassos.relato.Templates;
import io.github.mateusvpassos.relato.check.FieldCheck;
import io.github.mateusvpassos.relato.model.ReportTemplate;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Linha de comando:
 * <pre>
 * relato render  relatorio.report.json [-d dados.json] [-o saida.pdf] [-f pdf|xlsx|html|csv] [-p nome=valor ...]
 * relato check   arquivo.report.json ... (sai com 1 se houver problema; bom para CI)
 * relato serve   [--port 8090] [--host 127.0.0.1] [--templates pasta] [--token segredo]
 * </pre>
 * Os dados seguem o formato do {@code *.sample.json}: {@code {"params": {...}, "data": [...]}}.
 */
public final class RelatoCli {
    private RelatoCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help")) {
            usage();
            System.exit(args.length == 0 ? 2 : 0);
        }
        quietLogs();
        String cmd = args[0];
        List<String> rest = List.of(args).subList(1, args.length);
        int code;
        try {
            code = switch (cmd) {
                case "render" -> render(rest);
                case "check" -> check(rest);
                case "serve" -> serve(rest);
                case "engine" -> {
                    RelatoEngine.run();
                    yield 0;
                }
                default -> {
                    System.err.println("comando desconhecido: " + cmd);
                    usage();
                    yield 2;
                }
            };
        } catch (ReportException | IllegalArgumentException e) {
            System.err.println("erro: " + e.getMessage());
            code = 1;
        }
        if (code != 0 || !cmd.equals("serve")) System.exit(code);
    }

    /** Sem os logs INFO do openhtmltopdf/fontbox na saída de erro (RELATO_VERBOSE=1 mantém). */
    private static void quietLogs() {
        if (System.getenv("RELATO_VERBOSE") != null) return;
        try {
            Class.forName("com.openhtmltopdf.util.XRLog").getMethod("setLoggingEnabled", boolean.class).invoke(null, false);
        } catch (ReflectiveOperationException ignored) {
            // versão sem XRLog: segue com os logs
        }
        for (String n : List.of("org.apache.fontbox", "org.apache.pdfbox", "com.openhtmltopdf")) {
            java.util.logging.Logger l = java.util.logging.Logger.getLogger(n);
            l.setLevel(java.util.logging.Level.SEVERE);
            QUIET.add(l);
        }
    }

    /** Referências fortes: o JUL esquece o nível de um logger coletado. */
    private static final List<java.util.logging.Logger> QUIET = new ArrayList<>();

    private static void usage() {
        System.err.println("""
                relato: relatórios a partir de *.report.json

                  relato render <relatorio.report.json> [-d dados.json] [-o saida.pdf] [-f pdf|xlsx|html|csv] [-p nome=valor ...]
                        dados: {"params": {...}, "data": [...]}; sem -d usa nome.sample.json ao lado, se existir
                        sem -o grava ao lado do template; "-o -" escreve na saída padrão
                  relato check <arquivo.report.json> ...
                        confere campos e expressões; sai com 1 se houver problema
                  relato serve [--port 8090] [--host 127.0.0.1] [--templates pasta] [--token segredo]
                        POST /render/{nome}?format=pdf  corpo {"params": {...}, "data": [...]}
                        POST /render?format=pdf         corpo {"template": {...}, "params": {...}, "data": [...]}
                        GET  /templates, GET /health
                """);
    }

    // ------------------------------------------------------------ render

    static int render(List<String> args) throws IOException {
        Path template = null, data = null, out = null;
        Format format = null;
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "-d", "--data" -> data = Path.of(value(args, ++i, a));
                case "-o", "--out" -> out = Path.of(value(args, ++i, a));
                case "-f", "--format" -> format = Format.of(value(args, ++i, a));
                case "-p", "--param" -> {
                    String kv = value(args, ++i, a);
                    int eq = kv.indexOf('=');
                    if (eq < 1) throw new IllegalArgumentException("parâmetro deve ser nome=valor: " + kv);
                    params.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
                default -> {
                    if (a.startsWith("-")) throw new IllegalArgumentException("opção desconhecida: " + a);
                    if (template != null) throw new IllegalArgumentException("só um template por vez");
                    template = Path.of(a);
                }
            }
        }
        if (template == null) throw new IllegalArgumentException("informe o arquivo .report.json");
        String base = template.getFileName().toString().replaceFirst("\\.report\\.json$|\\.json$", "");
        if (data == null) {
            Path sample = template.resolveSibling(base + ".sample.json");
            if (Files.isRegularFile(sample)) data = sample;
        }
        boolean stdout = out != null && out.toString().equals("-");
        if (format == null) format = out != null && !stdout ? formatOf(out) : Format.PDF;
        if (out == null) out = template.resolveSibling(base + "." + format.extension());

        ReportTemplate t = Templates.parse(Files.readString(template));
        SampleData s = data == null ? new SampleData(new LinkedHashMap<>(), List.of()) : SampleData.parse(Files.readString(data), t);
        Map<String, Object> all = new LinkedHashMap<>(s.params());
        all.putAll(params);
        Relato relato = Relato.builder()
                .templates(io.github.mateusvpassos.relato.TemplateLoader.directory(template.toAbsolutePath().getParent()))
                .resources(io.github.mateusvpassos.relato.ResourceResolver.standard(null, template.toAbsolutePath().getParent()))
                .build();
        byte[] bytes = relato.report(t).data(s.data()).params(all).render(format);
        if (stdout) {
            OutputStream o = System.out;
            o.write(bytes);
            o.flush();
        } else {
            Files.write(out, bytes);
            System.err.println(out + " (" + bytes.length / 1024 + " KB)");
        }
        return 0;
    }

    private static Format formatOf(Path out) {
        String n = out.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? Format.PDF : Format.of(n.substring(dot + 1));
    }

    private static String value(List<String> args, int i, String opt) {
        if (i >= args.size()) throw new IllegalArgumentException(opt + " precisa de um valor");
        return args.get(i);
    }

    // ------------------------------------------------------------ check

    static int check(List<String> files) throws IOException {
        if (files.isEmpty()) throw new IllegalArgumentException("informe os arquivos .report.json");
        int problems = 0;
        List<Path> all = new ArrayList<>();
        for (String f : files) {
            Path p = Path.of(f);
            if (Files.isDirectory(p)) {
                try (var s = Files.walk(p)) {
                    s.filter(x -> x.toString().endsWith(".report.json")).sorted().forEach(all::add);
                }
            } else {
                all.add(p);
            }
        }
        for (Path p : all) {
            String json = Files.readString(p);
            try {
                Templates.parse(json);
            } catch (ReportException e) {
                System.out.println(p + ": " + e.getMessage());
                problems++;
                continue;
            }
            for (FieldCheck.Problem pr : FieldCheck.check(json)) {
                System.out.println(p + ": " + pr.pathString() + ": " + pr.message());
                problems++;
            }
        }
        System.err.println(all.size() + " arquivo(s), " + problems + " problema(s)");
        return problems == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------ serve

    static int serve(List<String> args) throws IOException {
        int port = 8090;
        String host = "127.0.0.1";
        Path dir = Path.of(".");
        String token = System.getenv("RELATO_TOKEN");
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--port" -> port = Integer.parseInt(value(args, ++i, a));
                case "--host" -> host = value(args, ++i, a);
                case "--templates" -> dir = Path.of(value(args, ++i, a));
                case "--token" -> token = value(args, ++i, a);
                default -> throw new IllegalArgumentException("opção desconhecida: " + a);
            }
        }
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("pasta de templates não existe: " + dir);
        RelatoServer server = RelatoServer.start(host, port, dir, token);
        System.err.println("relato em http://" + host + ":" + server.port() + " · templates: " + dir.toAbsolutePath().normalize()
                + (token == null || token.isBlank() ? "" : " · exige Authorization: Bearer <token>"));
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host) && (token == null || token.isBlank())) {
            System.err.println("aviso: escutando fora do localhost sem --token; qualquer um na rede pode gerar relatórios");
        }
        return 0;
    }
}
