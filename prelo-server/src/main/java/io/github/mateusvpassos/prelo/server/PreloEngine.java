package io.github.mateusvpassos.prelo.server;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mateusvpassos.prelo.Format;
import io.github.mateusvpassos.prelo.Prelo;
import io.github.mateusvpassos.prelo.ReportException;
import io.github.mateusvpassos.prelo.ResourceResolver;
import io.github.mateusvpassos.prelo.SampleData;
import io.github.mateusvpassos.prelo.TemplateLoader;
import io.github.mateusvpassos.prelo.Templates;
import io.github.mateusvpassos.prelo.check.FieldCheck;
import io.github.mateusvpassos.prelo.model.ReportTemplate;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Motor do app desktop: um processo que fala JSON por linha em stdin/stdout.
 * Pedido {@code {"id": 1, "cmd": "preview", ...}}, resposta {@code {"id": 1, ...}} (ou {@code "error"}).
 *
 * <ul>
 *   <li>{@code preview}: {@code json, sample?, baseDir?, dpi?} → {@code pages[] (data URI PNG), total, notes[], error?}</li>
 *   <li>{@code check}: {@code json} → {@code items[] {path, message}}</li>
 *   <li>{@code export}: {@code json, sample?, baseDir?, format, out} → {@code bytes}</li>
 *   <li>{@code sample}: {@code json, rows?} → {@code text} (sample inventado a partir de {@code fields})</li>
 *   <li>{@code schema} → {@code schema} (texto do prelo.schema.json)</li>
 * </ul>
 * stdout é só do protocolo: {@code System.out} é desviado para stderr.
 */
public final class PreloEngine {
    static final int SAMPLE_ROWS = 40;
    static final int MAX_PAGES = 40;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA);

    private PreloEngine() {}

    public static void run() throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        out.println("{\"event\":\"ready\"}");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String req = line;
            pool.submit(() -> {
                String resp = handle(req);
                synchronized (out) {
                    out.println(resp);
                }
            });
        }
        pool.shutdown();
    }

    static String handle(String line) {
        ObjectNode resp = JSON.createObjectNode();
        try {
            JsonNode req = JSON.readTree(line);
            resp.set("id", req.get("id"));
            switch (req.path("cmd").asText()) {
                case "preview" -> preview(req, resp);
                case "check" -> {
                    List<Object> items = new ArrayList<>();
                    for (FieldCheck.Problem p : FieldCheck.check(req.path("json").asText())) {
                        items.add(java.util.Map.of("path", p.path(), "message", p.message()));
                    }
                    resp.set("items", JSON.valueToTree(items));
                }
                case "export" -> {
                    Inputs in = inputs(req, null);
                    Format f = Format.of(req.path("format").asText("pdf"));
                    byte[] b = prelo(in.baseDir).report(in.template).data(in.sample.data()).params(in.sample.params()).render(f);
                    Files.write(Path.of(req.path("out").asText()), b);
                    resp.put("bytes", b.length);
                }
                case "sample" -> {
                    ReportTemplate t = Templates.parse(req.path("json").asText());
                    resp.put("text", SampleData.generate(t, req.path("rows").asInt(SAMPLE_ROWS)).toJson());
                }
                case "schema" -> {
                    try (InputStream s = PreloEngine.class.getResourceAsStream("/prelo.schema.json")) {
                        resp.put("schema", new String(s.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
                default -> resp.put("error", "comando desconhecido: " + req.path("cmd").asText());
            }
        } catch (ReportException | IllegalArgumentException e) {
            resp.put("error", e.getMessage());
        } catch (Exception | StackOverflowError e) {
            resp.put("error", describe(e));
        }
        try {
            return JSON.writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"error\":\"falha ao responder\"}";
        }
    }

    private record Inputs(ReportTemplate template, SampleData sample, Path baseDir) {}

    private static Inputs inputs(JsonNode req, List<String> notes) {
        ReportTemplate t = Templates.parse(req.path("json").asText());
        Path base = req.hasNonNull("baseDir") ? Path.of(req.get("baseDir").asText()) : null;
        String sampleText = req.hasNonNull("sample") ? req.get("sample").asText() : null;
        SampleData s;
        if (sampleText != null && !sampleText.isBlank()) {
            s = SampleData.parse(sampleText, t);
        } else {
            if (notes != null) {
                notes.add(t.fields().isEmpty()
                        ? "Sem 'fields' nem dados de exemplo: tabelas e listas ficam vazias. Declare os campos ou crie os dados de exemplo."
                        : "Dados inventados a partir de 'fields' (" + SAMPLE_ROWS + " linhas).");
            }
            s = SampleData.generate(t, SAMPLE_ROWS);
        }
        return new Inputs(t, s, base);
    }

    private static void preview(JsonNode req, ObjectNode resp) throws Exception {
        List<String> notes = new ArrayList<>();
        Inputs in;
        try {
            in = inputs(req, notes);
        } catch (ReportException e) {
            resp.put("error", e.getMessage());
            resp.set("pages", JSON.createArrayNode());
            return;
        }
        resp.set("notes", JSON.valueToTree(notes));
        byte[] pdf;
        try {
            pdf = prelo(in.baseDir).report(in.template).data(in.sample.data()).params(in.sample.params()).pdf();
        } catch (ReportException e) {
            resp.put("error", e.getMessage());
            resp.set("pages", JSON.createArrayNode());
            return;
        }
        float dpi = (float) req.path("dpi").asDouble(80);
        List<String> pages = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFRenderer r = new PDFRenderer(d);
            int n = d.getNumberOfPages();
            for (int i = 0; i < Math.min(n, MAX_PAGES); i++) {
                BufferedImage img = r.renderImageWithDPI(i, dpi, ImageType.RGB);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                ImageIO.write(img, "png", o);
                pages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(o.toByteArray()));
            }
            resp.put("total", n);
        }
        resp.set("pages", JSON.valueToTree(pages));
    }

    private static Prelo prelo(Path baseDir) {
        Prelo.Builder b = Prelo.builder().cache(false)
                .resources(ResourceResolver.standard(PreloEngine.class.getClassLoader(), baseDir));
        if (baseDir != null) b.templates(TemplateLoader.directory(baseDir));
        return b.build();
    }

    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Throwable t = e; t != null && n < 5; t = t.getCause(), n++) {
            if (n > 0) sb.append("\ncausa: ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }
}
