package io.github.mateusvpassos.relato.model;

/**
 * Proteção do PDF (AES-256). Senhas aceitam expressões (ex.: {@code ${param.cpf}}).
 *
 * @param userPassword  senha para abrir; vazio = abre sem senha (mas respeita as permissões)
 * @param ownerPassword senha de dono (libera tudo); padrão: aleatória
 * @param allowPrint    padrão true
 * @param allowCopy     copiar texto; padrão true
 * @param allowModify   editar; padrão false
 */
public record Security(String userPassword, String ownerPassword, Boolean allowPrint, Boolean allowCopy, Boolean allowModify) {}
