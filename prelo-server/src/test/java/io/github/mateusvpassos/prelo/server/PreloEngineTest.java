package io.github.mateusvpassos.prelo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreloEngineTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String TPL = "{\"fields\":[{\"name\":\"nome\"}],\"body\":[{\"type\":\"table\",\"columns\":[{\"header\":\"Nome\",\"value\":\"${nome}\"}]}]}";

    static JsonNode call(Map<String, Object> req) throws Exception {
        return M.readTree(PreloEngine.handle(M.writeValueAsString(req)));
    }

    @Test
    void previewCheckSampleExportSchema(@TempDir Path dir) throws Exception {
        JsonNode p = call(Map.of("id", 1, "cmd", "preview", "json", TPL, "sample", "{\"data\":[{\"nome\":\"Ana\"}]}"));
        assertEquals(1, p.path("id").asInt());
        assertEquals(1, p.path("total").asInt(), p.toString());
        assertTrue(p.path("pages").get(0).asText().startsWith("data:image/png;base64,"));

        JsonNode inv = call(Map.of("id", 2, "cmd", "preview", "json", TPL));
        assertTrue(inv.path("notes").get(0).asText().contains("inventados"), inv.toString());

        JsonNode c = call(Map.of("id", 3, "cmd", "check", "json", TPL.replace("${nome}", "${nme}")));
        assertEquals("body[0].columns[0].value", String.join("", c.path("items").get(0).path("path").toString()
                .replace("[\"body\",0,\"columns\",0,\"value\"]", "body[0].columns[0].value")));

        JsonNode s = call(Map.of("id", 4, "cmd", "sample", "json", TPL, "rows", 3));
        assertEquals(3, M.readTree(s.path("text").asText()).path("data").size());

        Path out = dir.resolve("x.xlsx");
        JsonNode e = call(Map.of("id", 5, "cmd", "export", "json", TPL, "format", "xlsx", "out", out.toString()));
        assertTrue(Files.size(out) > 1000, e.toString());

        assertTrue(call(Map.of("id", 6, "cmd", "schema")).path("schema").asText().contains("\"chart\""));
        assertTrue(call(Map.of("id", 7, "cmd", "preview", "json", "{\"body\":[{\"type\":\"text\",\"value\":\"${1 +}\"}]}")).has("error"));
    }
}
