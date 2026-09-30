package io.github.mateusvpassos.relato.quarkus;

import io.github.mateusvpassos.relato.Format;
import io.github.mateusvpassos.relato.ReportRequest;
import jakarta.ws.rs.core.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Respostas JAX-RS prontas (Quarkus REST / RESTEasy).
 *
 * <pre>{@code
 * @GET @Path("/relatorios/vendas")
 * public Response vendas(@QueryParam("periodo") String periodo) {
 *     return ReportResponses.inline(relato.report("vendas").data(service.vendas(periodo)), Format.PDF, "vendas");
 * }
 * }</pre>
 */
public final class ReportResponses {
    private ReportResponses() {}

    /** Abre no navegador (PDF/HTML). */
    public static Response inline(ReportRequest report, Format format, String fileName) {
        return build(report, format, fileName, "inline");
    }

    /** Força download. */
    public static Response download(ReportRequest report, Format format, String fileName) {
        return build(report, format, fileName, "attachment");
    }

    private static Response build(ReportRequest report, Format format, String fileName, String disposition) {
        byte[] body = report.render(format);
        String name = fileName.endsWith("." + format.extension()) ? fileName : fileName + "." + format.extension();
        String ascii = name.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "");
        String utf8 = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return Response.ok(body)
                .type(format.contentType())
                .header("Content-Disposition", disposition + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8)
                .header("Content-Length", body.length)
                .build();
    }
}
