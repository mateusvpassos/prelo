package demo;

import io.github.mateusvpassos.relato.Format;
import io.github.mateusvpassos.relato.Relato;
import io.github.mateusvpassos.relato.quarkus.ReportResponses;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Path("/relatorios")
public class RelatoriosResource {
    @Inject
    Relato relato;

    /** /relatorios/vendas?formato=pdf|xlsx|html */
    @GET
    @Path("/vendas")
    public Response vendas(@QueryParam("formato") @DefaultValue("pdf") String formato) {
        var report = relato.report("vendas").data(vendas(120)).param("periodo", "09/2026");
        return ReportResponses.inline(report, Format.of(formato), "vendas");
    }

    private static List<Venda> vendas(int n) {
        String[] regioes = {"Sul", "Sudeste", "Norte"};
        List<Venda> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Venda(regioes[i % 3], "Vendedor " + (i % 4), new Venda.Cliente("Cliente " + i, "1234567800019" + (i % 10)),
                    LocalDate.of(2026, 9, 1 + i % 28), 1 + i % 7, BigDecimal.valueOf(1000 + i * 37L, 2)));
        }
        return out;
    }
}
