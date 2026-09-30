package demo;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Venda(String regiao, String vendedor, Cliente cliente, LocalDate data, int quantidade, BigDecimal valor) {
    public record Cliente(String nome, String cnpj) {}
}
