package io.github.mateusvpassos.prelo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreloServerTest {
    @TempDir
    Path dir;
    PreloServer server;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Files.writeString(dir.resolve("ola.report.json"), """
                {"parameters":[{"name":"quem"}],"fields":[{"name":"valor","type":"money"}],
                 "body":[{"type":"text","value":"Olá ${quem}"},{"type":"table","columns":[{"header":"Valor","value":"${valor}","format":"currency","total":"sum"}]}]}
                """);
        Files.writeString(dir.resolve("segredo.txt"), "x");
        server = PreloServer.start("127.0.0.1", 0, dir, "abc");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<byte[]> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void rendersNamedTemplate() throws Exception {
        var r = post("/render/ola?format=pdf", "{\"params\":{\"quem\":\"Ana\"},\"data\":[{\"valor\":10},{\"valor\":2.5}]}", "abc");
        assertEquals(200, r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        assertEquals("application/pdf", r.headers().firstValue("Content-Type").orElse(""));
        try (PDDocument d = Loader.loadPDF(r.body())) {
            String txt = new PDFTextStripper().getText(d);
            assertTrue(txt.contains("Olá Ana") && txt.contains("12,50"), txt);
        }
        var csv = post("/render/ola?format=csv", "{\"data\":[{\"valor\":10}]}", "abc");
        assertEquals(200, csv.statusCode());
        assertTrue(new String(csv.body(), StandardCharsets.UTF_8).contains("Valor\r\n10"));
    }

    @Test
    void rendersInlineTemplate() throws Exception {
        var r = post("/render?format=html", "{\"template\":{\"body\":[{\"type\":\"text\",\"value\":\"${1+1}\"}]}}", "abc");
        assertEquals(200, r.statusCode());
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains(">2<"));
    }

    @Test
    void rejectsBadTokenTraversalAndErrors() throws Exception {
        assertEquals(401, post("/render/ola", "{}", null).statusCode());
        assertEquals(401, post("/render/ola", "{}", "errado").statusCode());
        assertEquals(400, post("/render/a..b", "{}", "abc").statusCode());
        assertEquals(404, post("/render/nao-existe", "{}", "abc").statusCode());
        var inc = post("/render", "{\"template\":{\"body\":[{\"type\":\"include\",\"template\":\"../segredo\"}]}}", "abc");
        assertEquals(400, inc.statusCode(), new String(inc.body(), StandardCharsets.UTF_8));
        var img = post("/render?format=html", "{\"template\":{\"body\":[{\"type\":\"image\",\"src\":\"${p}\"}]},\"params\":{\"p\":\"../segredo.txt\"}}", "abc");
        assertTrue(!new String(img.body(), StandardCharsets.UTF_8).contains("data:"), "não lê fora da pasta");
        var bad = post("/render/ola", "{\"data\":[{\"valor\":1}],\"params\":{}}".replace("ola", "ola"), "abc");
        assertEquals(200, bad.statusCode());
        var syntax = post("/render", "{\"template\":{\"body\":[{\"type\":\"text\",\"value\":\"${1 +}\"}]}}", "abc");
        assertEquals(400, syntax.statusCode());
        assertTrue(new String(syntax.body(), StandardCharsets.UTF_8).contains("error"));
    }

    @Test
    void listsTemplatesAndHealth() throws Exception {
        var h = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, h.statusCode());
        var t = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/templates")).header("Authorization", "Bearer abc").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("[\"ola\"]", t.body());
    }

    @Test
    void cliRenderAndCheck() throws Exception {
        Files.writeString(dir.resolve("ola.sample.json"), "{\"params\":{\"quem\":\"Bia\"},\"data\":[{\"valor\":3}]}");
        assertEquals(0, PreloCli.render(List.of(dir.resolve("ola.report.json").toString(), "-f", "xlsx")));
        assertTrue(Files.size(dir.resolve("ola.xlsx")) > 1000);
        assertEquals(0, PreloCli.render(List.of(dir.resolve("ola.report.json").toString(), "-o", dir.resolve("x.pdf").toString(), "-p", "quem=Caio")));
        try (PDDocument d = Loader.loadPDF(Files.readAllBytes(dir.resolve("x.pdf")))) {
            assertTrue(new PDFTextStripper().getText(d).contains("Olá Caio"));
        }
        assertEquals(0, PreloCli.check(List.of(dir.toString())));
        Files.writeString(dir.resolve("ruim.report.json"), "{\"fields\":[{\"name\":\"a\"}],\"body\":[{\"type\":\"text\",\"value\":\"${b}\"}]}");
        assertEquals(1, PreloCli.check(List.of(dir.toString())));
    }
}
