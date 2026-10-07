package io.github.mateusvpassos.relato.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Códigos brasileiros: payload do Pix "copia e cola" (BR Code, EMV) e código de barras de boleto a partir da
 * linha digitável.
 */
public final class BrCodes {
    private BrCodes() {}

    /**
     * Payload Pix estático (BR Code). Use num bloco {@code barcode} com {@code format: "qr"} e/ou mostre como
     * "copia e cola".
     *
     * @param key   chave Pix (CPF/CNPJ só dígitos, e-mail, telefone +55..., ou chave aleatória)
     * @param amount valor (null ou 0 = o pagador digita)
     * @param name  nome do recebedor (até 25)
     * @param city  cidade do recebedor (até 15)
     * @param txid  identificador (até 25, letras e números; padrão ***)
     * @param description informação adicional (opcional)
     */
    public static String pix(String key, BigDecimal amount, String name, String city, String txid, String description) {
        if (key == null || key.isBlank()) throw new ExprException("pix(): chave obrigatória");
        StringBuilder mai = new StringBuilder(tlv("00", "br.gov.bcb.pix")).append(tlv("01", key.trim()));
        if (description != null && !description.isBlank()) mai.append(tlv("02", clean(description, 72)));
        StringBuilder p = new StringBuilder();
        p.append(tlv("00", "01"));
        p.append(tlv("26", mai.toString()));
        p.append(tlv("52", "0000"));
        p.append(tlv("53", "986"));
        if (amount != null && amount.signum() > 0) p.append(tlv("54", amount.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        p.append(tlv("58", "BR"));
        p.append(tlv("59", clean(name == null || name.isBlank() ? "RECEBEDOR" : name, 25)));
        p.append(tlv("60", clean(city == null || city.isBlank() ? "BRASIL" : city, 15)));
        String id = txid == null || txid.isBlank() ? "***" : txid.replaceAll("[^A-Za-z0-9]", "");
        if (id.length() > 25) id = id.substring(0, 25);
        if (id.isEmpty()) id = "***";
        p.append(tlv("62", tlv("05", id)));
        p.append("6304");
        return p + crc16(p.toString());
    }

    /**
     * Linha digitável de boleto bancário (47 dígitos) → código de barras de 44 dígitos (ITF, use {@code format: "itf"}).
     * Aceita a linha com ou sem pontos/espaços. Convênio (48 dígitos, começa com 8) também é aceito.
     */
    public static String boletoBarcode(String linha) {
        String d = linha == null ? "" : linha.replaceAll("\\D", "");
        if (d.length() == 47) {
            return d.substring(0, 4) + d.charAt(32) + d.substring(33, 47) + d.substring(4, 9) + d.substring(10, 20) + d.substring(21, 31);
        }
        if (d.length() == 48 && d.charAt(0) == '8') {
            return d.substring(0, 11) + d.substring(12, 23) + d.substring(24, 35) + d.substring(36, 47);
        }
        if (d.length() == 44) return d;
        throw new ExprException("boleto(): linha digitável deve ter 47 (ou 48, convênio) dígitos; veio " + d.length());
    }

    static String tlv(String id, String value) {
        return id + String.format("%02d", value.length()) + value;
    }

    /** Sem acentos e só caracteres seguros, como pede o padrão. */
    private static String clean(String s, int max) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9 .,@/-]", "").trim();
        n = n.toUpperCase(Locale.ROOT);
        return n.length() > max ? n.substring(0, max) : n;
    }

    /** CRC16-CCITT (polinômio 0x1021, inicial 0xFFFF), em hexa maiúsculo. */
    static String crc16(String s) {
        int crc = 0xFFFF;
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return String.format("%04X", crc);
    }
}
