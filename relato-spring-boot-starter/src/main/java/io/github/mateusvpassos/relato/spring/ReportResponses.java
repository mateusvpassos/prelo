package io.github.mateusvpassos.relato.spring;

import io.github.mateusvpassos.relato.Format;
import io.github.mateusvpassos.relato.ReportRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Respostas HTTP prontas (requer spring-web).
 *
 * <pre>{@code
 * @GetMapping("/relatorios/vendas")
 * ResponseEntity<byte[]> vendas(@RequestParam String periodo) {
 *     return ReportResponses.inline(relato.report("vendas").data(service.vendas(periodo)).param("periodo", periodo),
 *             Format.PDF, "vendas-" + periodo);
 * }
 * }</pre>
 */
public final class ReportResponses {
    private ReportResponses() {}

    /** Abre no navegador (PDF/HTML). */
    public static ResponseEntity<byte[]> inline(ReportRequest report, Format format, String fileName) {
        return build(report, format, fileName, false);
    }

    /** Força download. */
    public static ResponseEntity<byte[]> download(ReportRequest report, Format format, String fileName) {
        return build(report, format, fileName, true);
    }

    private static ResponseEntity<byte[]> build(ReportRequest report, Format format, String fileName, boolean attachment) {
        byte[] body = report.render(format);
        String name = fileName.endsWith("." + format.extension()) ? fileName : fileName + "." + format.extension();
        ContentDisposition cd = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(name, java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentLength(body.length)
                .body(body);
    }
}
