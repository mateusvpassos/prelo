package demo;

import io.github.mateusvpassos.relato.Format;
import io.github.mateusvpassos.relato.Relato;
import io.github.mateusvpassos.relato.spring.ReportResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RelatoriosController {
    private final Relato relato;
    private final Dados dados;

    public RelatoriosController(Relato relato, Dados dados) {
        this.relato = relato;
        this.dados = dados;
    }

    /** /relatorios/vendas?formato=pdf|xlsx|html&periodo=09/2026 */
    @GetMapping("/relatorios/vendas")
    public ResponseEntity<byte[]> vendas(@RequestParam(defaultValue = "pdf") String formato,
                                        @RequestParam(defaultValue = "09/2026") String periodo) {
        var report = relato.report("vendas")           // src/main/resources/reports/vendas.report.json
                .data(dados.vendas(180))                // lista → vira "rows", fonte da tabela
                .param("periodo", periodo);             // ${periodo} no template
        return ReportResponses.inline(report, Format.of(formato), "vendas");
    }

    @GetMapping("/relatorios/pedidos")
    public ResponseEntity<byte[]> pedidos(@RequestParam(defaultValue = "pdf") String formato) {
        return ReportResponses.inline(relato.report("pedidos").data(dados.pedidos()), Format.of(formato), "pedidos");
    }
}
