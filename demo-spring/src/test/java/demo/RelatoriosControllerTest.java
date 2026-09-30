package demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RelatoriosControllerTest {
    @Autowired
    MockMvc mvc;

    @Test
    void vendasPdf() throws Exception {
        mvc.perform(get("/relatorios/vendas"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("vendas.pdf")));
    }

    @Test
    void vendasXlsxEPedidosHtml() throws Exception {
        mvc.perform(get("/relatorios/vendas?formato=xlsx")).andExpect(status().isOk());
        mvc.perform(get("/relatorios/pedidos?formato=html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Entregar pela manhã")));
    }
}
