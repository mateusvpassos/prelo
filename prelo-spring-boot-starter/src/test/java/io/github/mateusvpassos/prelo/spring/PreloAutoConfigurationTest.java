package io.github.mateusvpassos.prelo.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mateusvpassos.prelo.Format;
import io.github.mateusvpassos.prelo.Prelo;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PreloAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PreloAutoConfiguration.class));

    @Test
    void registraPreloEGeraPdf() {
        runner.run(ctx -> {
            Prelo prelo = ctx.getBean(Prelo.class);
            assertThat(prelo.report("ola").data(Map.of("nome", "mundo")).html()).contains("Olá mundo");
            var resp = ReportResponses.download(prelo.report("ola").data(Map.of("nome", "x")), Format.PDF, "ola");
            assertThat(resp.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains("ola.pdf");
            assertThat(new String(resp.getBody(), 0, 5)).isEqualTo("%PDF-");
        });
    }
}
