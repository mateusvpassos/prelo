package io.github.mateusvpassos.relato.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração {@code relato.*}. */
@ConfigurationProperties(prefix = "relato")
public class RelatoProperties {
    /** Onde ficam os templates: {@code classpath:reports/} ou uma pasta ({@code file:/caminho}). */
    private String templates = "classpath:reports/";
    /** Guarda templates lidos em memória. Desligue em desenvolvimento para ver edições sem reiniciar. */
    private boolean cache = true;
    private String locale = "pt-BR";
    private String zone = "America/Sao_Paulo";
    private String datePattern = "dd/MM/yyyy";
    private String dateTimePattern = "dd/MM/yyyy HH:mm";
    /** Tabelas com mais linhas que isso geram o PDF em blocos (memória limitada); 0 desliga. */
    private int chunkRows = 4000;

    public String getTemplates() { return templates; }
    public void setTemplates(String templates) { this.templates = templates; }
    public boolean isCache() { return cache; }
    public void setCache(boolean cache) { this.cache = cache; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public String getDatePattern() { return datePattern; }
    public void setDatePattern(String datePattern) { this.datePattern = datePattern; }
    public String getDateTimePattern() { return dateTimePattern; }
    public void setDateTimePattern(String dateTimePattern) { this.dateTimePattern = dateTimePattern; }
    public int getChunkRows() { return chunkRows; }
    public void setChunkRows(int chunkRows) { this.chunkRows = chunkRows; }
}
