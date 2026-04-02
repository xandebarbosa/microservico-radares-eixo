package com.coruja.entities;

import com.coruja.enuns.StatusProcessamento;
import com.coruja.enuns.TipoFonte;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Rastreia cada arquivo SFTP já baixado/processado.
 *
 * <p>Substitui os sets em memória ({@code arquivosBaixadosRecebidos / arquivosBaixadosRadar})
 * do {@code SftpDownloader}, tornando o controle de duplicatas <b>persistente</b> entre
 * restarts do container.
 *
 * <p>Permite ainda auditoria, reprocessamento seletivo e alertas de falha.
 */
@Entity
@Table(
        name = "arquivo_sftp_processado",
        indexes = {
                @Index(name = "idx_arquivo_nome",        columnList = "nome_arquivo", unique = true),
                @Index(name = "idx_arquivo_status",      columnList = "status"),
                @Index(name = "idx_arquivo_tipo_fonte",  columnList = "tipo_fonte"),
                @Index(name = "idx_arquivo_processado_em", columnList = "processado_em DESC")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArquivoSftpProcessado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nome exato do arquivo remoto (ex: {@code EIXOSP_2026-02-23T14-38-176641.csv}). */
    @Column(name = "nome_arquivo", nullable = false, unique = true, length = 255)
    private String nomeArquivo;

    /** Origem: RECEBIDOS (sem KM) ou RADAR (com KM). */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_fonte", nullable = false, length = 20)
    private TipoFonte tipoFonte;

    /**
     * Ciclo de vida do arquivo:
     * {@code PENDENTE → BAIXADO → PROCESSADO | ERRO}
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatusProcessamento status = StatusProcessamento.PENDENTE;

    /** Quantos registros foram persistidos no banco a partir deste arquivo. */
    @Column(name = "registros_salvos")
    private Integer registrosSalvos;

    /** Quando o arquivo foi baixado do SFTP. */
    @Column(name = "baixado_em")
    private LocalDateTime baixadoEm;

    /** Quando o processamento (parse + insert) terminou. */
    @Column(name = "processado_em")
    private LocalDateTime processadoEm;

    /** Mensagem de erro, preenchida apenas quando {@code status == ERRO}. */
    @Column(name = "mensagem_erro", length = 1000)
    private String mensagemErro;

    /** Número de tentativas de processamento (para retry lógico futuro). */
    @Column(nullable = false)
    @Builder.Default
    private int tentativas = 0;
}
