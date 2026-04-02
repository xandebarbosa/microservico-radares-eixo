package com.coruja.enuns;

/**
 * Ciclo de vida de um arquivo SFTP dentro do pipeline de ingestão.
 *
 * <pre>
 *   PENDENTE ──► BAIXADO ──► PROCESSADO
 *                    └──────► ERRO  (pode ser retentado)
 * </pre>
 */
public enum StatusProcessamento {

    /** Arquivo identificado no SFTP, download ainda não iniciado. */
    PENDENTE,

    /** Download concluído; aguardando parse e insert no banco. */
    BAIXADO,

    /** Parse e persistência concluídos com sucesso. */
    PROCESSADO,

    /** Falha durante download ou processamento; campo {@code mensagemErro} preenchido. */
    ERRO
}
