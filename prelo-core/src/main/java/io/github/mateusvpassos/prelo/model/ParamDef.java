package io.github.mateusvpassos.prelo.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parâmetro do relatório, acessível como {@code ${param.nome}} (ou só {@code ${nome}}).
 *
 * @param type          string, number, integer, decimal, money, date, datetime, boolean
 * @param defaultValue  usado quando o parâmetro não é informado
 * @param sample        valor usado no preview
 */
public record ParamDef(String name, String type, String label, @JsonProperty("default") Object defaultValue, Object sample) {}
