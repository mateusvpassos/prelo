package io.github.mateusvpassos.relato.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mateusvpassos.relato.Format;
import io.github.mateusvpassos.relato.Relato;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RelatoAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RelatoAutoConfiguration.class));

    @Test
    void registraRelatoEGeraPdf() {
        runner.run(ctx -> {
            Relato relato = ctx.getBean(Relato.class);
            assertThat(relato.report("ola").data(Map.of("nome", "mundo")).html()).contains("Olá mundo");
            var resp = ReportResponses.download(relato.report("ola").data(Map.of("nome", "x")), Format.PDF, "ola");
            assertThat(resp.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains("ola.pdf");
            assertThat(new String(resp.getBody(), 0, 5)).isEqualTo("%PDF-");
        });
    }
}
