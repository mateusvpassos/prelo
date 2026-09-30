package demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Service;

/** Dados inventados. No sistema real isso viria do banco (JPA, JDBC...). */
@Service
public class Dados {

    public record Cliente(String nome, String cnpj) {}

    public record Venda(String regiao, String vendedor, Cliente cliente, LocalDate data, int quantidade, BigDecimal valor) {}

    public record Item(String produto, int qtd, BigDecimal preco) {}

    public record Pedido(int numero, String cliente, LocalDate emissao, boolean pago, List<Item> itens, String obs) {}

    private static final String[] REGIOES = {"Sul", "Sudeste", "Norte", "Nordeste"};
    private static final String[] CLIENTES = {"Padaria Pão Quente", "Mercado Bom Preço", "Farmácia Saúde", "Auto Peças Curitiba", "Livraria Saber"};

    public List<Venda> vendas(int quantidade) {
        Random r = new Random(42);
        List<Venda> out = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            out.add(new Venda(REGIOES[i % REGIOES.length], "Vendedor " + (i % 3 + 1),
                    new Cliente(CLIENTES[r.nextInt(CLIENTES.length)], String.valueOf(10_000_000_000_000L + r.nextInt(1_000_000))),
                    LocalDate.of(2026, 9, 1 + r.nextInt(29)), 1 + r.nextInt(9), BigDecimal.valueOf(1000 + r.nextInt(900_000), 2)));
        }
        return out;
    }

    public List<Pedido> pedidos() {
        List<Pedido> out = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            List<Item> itens = new ArrayList<>();
            for (int j = 1; j <= 2 + i % 4; j++) itens.add(new Item("Produto " + j, j, BigDecimal.valueOf(990L * j, 2)));
            out.add(new Pedido(1000 + i, CLIENTES[i % CLIENTES.length], LocalDate.of(2026, 9, i), i % 2 == 0, itens,
                    i % 3 == 0 ? "Entregar pela manhã" : null));
        }
        return out;
    }
}
