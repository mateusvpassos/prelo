package io.github.mateusvpassos.prelo.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.mateusvpassos.prelo.Format;
import io.github.mateusvpassos.prelo.Prelo;
import io.github.mateusvpassos.prelo.ReportException;
import io.github.mateusvpassos.prelo.ResourceResolver;
import io.github.mateusvpassos.prelo.SampleData;
import io.github.mateusvpassos.prelo.TemplateLoader;
import io.github.mateusvpassos.prelo.Templates;
import io.github.mateusvpassos.prelo.model.ReportTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Serviço HTTP mínimo (JDK {@link HttpServer}, sem framework) para gerar relatórios de qualquer linguagem.
 *
 * <ul>
 *   <li>{@code POST /render/{nome}?format=pdf}: template {@code nome.report.json} da pasta; corpo {@code {"params", "data"}}</li>
 *   <li>{@code POST /render?format=pdf}: template no corpo, {@code {"template": {...}, "params", "data"}}</li>
 *   <li>{@code GET /templates}: nomes disponíveis; {@code GET /health}</li>
 * </ul>
 * Imagens com caminho relativo são lidas só de dentro da pasta de templates.
 */
public final class PreloServer {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_\\-/]*");
    private static final int MAX_BODY = 64 * 1024 * 1024;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final HttpServer http;
    private final ExecutorService pool;
    private final Path dir;
    private final String token;
    private final Prelo prelo;

    private PreloServer(HttpServer http, Path dir, String token) {
        this.http = http;
        this.dir = dir.toAbsolutePath().normalize();
        this.token = token == null || token.isBlank() ? null : token;
        this.pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        this.prelo = Prelo.builder()
                .templates(confined(TemplateLoader.directory(this.dir)))
                .cache(false)
                .resources(confined(this.dir))
                .build();
        http.setExecutor(pool);
        http.createContext("/", this::handle);
    }

    public static PreloServer start(String host, int port, Path dir, String token) throws IOException {
        PreloServer s = new PreloServer(HttpServer.create(new InetSocketAddress(host, port), 0), dir, token);
        s.http.start();
        return s;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
        pool.shutdownNow();
    }

    /** Só nomes simples (também nos {@code include} de templates enviados no corpo). */
    static TemplateLoader confined(TemplateLoader inner) {
        return name -> {
            if (!validName(name)) throw new ReportException("nome de template inválido: " + name);
            return inner.load(name);
        };
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.contains("..") && !name.contains("//");
    }

    /** Leitor de recursos preso à pasta: sem caminho absoluto nem "..", além do classpath. */
    static ResourceResolver confined(Path root) {
        ResourceResolver std = ResourceResolver.standard(null, root);
        return location -> {
            if (location.startsWith("classpath:")) return std.read(location);
            String p = location.startsWith("file:") ? location.substring(5) : location;
            Path resolved = root.resolve(p).normalize();
            if (!resolved.startsWith(root)) return null;
            return std.read(resolved.toString());
        };
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/health")) {
                send(ex, 200, "application/json", "{\"status\":\"ok\"}");
                return;
            }
            if (token != null && !authorized(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                error(ex, 401, "token inválido ou ausente");
                return;
            }
            if (path.equals("/templates") && method.equals("GET")) {
                send(ex, 200, "application/json", JSON.writeValueAsString(templates()));
                return;
            }
            if (path.equals("/render") || path.startsWith("/render/")) {
                if (!method.equals("POST")) {
                    error(ex, 405, "use POST");
                    return;
                }
                render(ex, path.equals("/render") ? null : path.substring("/render/".length()));
                return;
            }
            error(ex, 404, "rota desconhecida: " + method + " " + path);
        } catch (ReportException | IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
        } catch (UncheckedIOException e) {
            error(ex, 500, e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            error(ex, 400, "JSON inválido: " + e.getOriginalMessage());
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("prelo: falha em " + ex.getRequestURI() + ": " + e);
            error(ex, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private void render(HttpExchange ex, String name) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        byte[] body = readBody(ex.getRequestBody());
        Map<String, Object> req = body.length == 0 ? Map.of()
                : JSON.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        ReportTemplate t;
        if (name == null) {
            Object tpl = req.get("template");
            if (!(tpl instanceof Map<?, ?>)) throw new IllegalArgumentException("corpo sem \"template\" (ou use /render/{nome})");
            t = Templates.parse(JSON.writeValueAsString(tpl));
        } else {
            if (!validName(name)) throw new IllegalArgumentException("nome de template inválido: " + name);
            if (!Files.isRegularFile(dir.resolve(name + ".report.json"))) {
                error(ex, 404, "template não encontrado: " + name);
                return;
            }
            t = prelo.template(name);
        }
        Object fmt = q.getOrDefault("format", req.get("format") instanceof String s ? s : "pdf");
        Format format = Format.of(String.valueOf(fmt));
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("params", req.getOrDefault("params", Map.of()));
        sample.put("data", req.getOrDefault("data", List.of()));
        SampleData s = SampleData.parse(JSON.writeValueAsString(sample), t);
        byte[] out = prelo.report(t).data(s.data()).params(s.params()).render(format);
        String file = (q.containsKey("filename") ? q.get("filename") : name == null ? "relatorio" : name.replace('/', '-'))
                .replaceAll("[^A-Za-z0-9._-]", "_") + "." + format.extension();
        ex.getResponseHeaders().add("Content-Disposition", ("1".equals(q.get("download")) ? "attachment" : "inline") + "; filename=\"" + file + "\"");
        send(ex, 200, format.contentType(), out);
    }

    private List<String> templates() throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".report.json"))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/').replaceFirst("\\.report\\.json$", ""))
                    .sorted().toList();
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(header.substring(7).trim().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] b = in.readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new IllegalArgumentException("corpo maior que " + MAX_BODY / 1024 / 1024 + " MB");
        return b;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
            m.put(k, eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void error(HttpExchange ex, int code, String msg) throws IOException {
        String json = JSON.writeValueAsString(Map.of("error", msg == null ? "erro" : msg));
        send(ex, code, "application/json", json);
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        send(ex, code, type + (type.contains("charset") ? "" : "; charset=UTF-8"), body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) ex.getResponseBody().write(body);
    }
}
