package com.coruja.services.sftp;

import com.coruja.entities.ArquivoSftpProcessado;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orquestra o ciclo completo SFTP com <b>processamento em lotes intercalados</b>.
 *
 * <h3>Fluxo do ciclo:</h3>
 * <pre>
 *   1. Abre conexão SFTP
 *   2. Para cada lote de arquivos baixados:
 *      a. Baixa {@code sftp.download.batch.size} arquivos (padrão: 50)
 *      b. Imediatamente processa e persiste os registros no banco
 *      c. Atualiza status de cada arquivo para PROCESSADO ou ERRO
 *   3. Ao final do ciclo principal, retenta arquivos com ERRO de ciclos anteriores
 *   4. Fecha conexão
 * </pre>
 *
 * <p>Benefícios versus o fluxo anterior (download tudo → processa tudo):
 * <ul>
 *   <li>Memória controlada: apenas {@code batchSize} arquivos em memória por vez.</li>
 *   <li>Falha parcial: um lote com erro não afeta os demais.</li>
 *   <li>Progresso visível: registros aparecem no banco muito antes do ciclo terminar.</li>
 *   <li>Rastreamento persistente: sobrevive a restarts do container.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SftpOrchestrator {

    private final SftpConnectionManager connectionManager;
    private final SftpDownloader        downloader;
    private final FileProcessor         fileProcessor;

    @PostConstruct
    public void init() {
        log.info(">>> SftpOrchestrator (Eixo) inicializado. Ciclos em lotes ativos.");
    }

    // ─────────────────────────────────────────────────────────────
    // CICLO PRINCIPAL — download + processamento intercalados
    // ─────────────────────────────────────────────────────────────

    @Scheduled(fixedRateString = "${sftp.schedule.rate.ms}")
    public void executarCiclo() {
        log.info("[SFTP] ════ Início do ciclo ════");
        long inicio = System.currentTimeMillis();

        try (SftpConnectionManager.SftpConnection conn = connectionManager.open()) {

            // O consumer é chamado A CADA LOTE durante o download.
            // Assim, enquanto o próximo lote está sendo baixado,
            // o lote anterior já foi processado e gravado no banco.
            SftpDownloader.DownloadSummary resumo = downloader.baixarEmLotes(
                    conn.channel(),
                    (lote, tipoFonte) -> {
                        log.info("[SFTP] Processando lote de {} arquivo(s) [{}]...",
                                lote.size(), tipoFonte);
                        FileProcessor.ResultadoLote resultado = fileProcessor.processar(lote, tipoFonte);
                        log.info("[SFTP] Lote processado: {} registros | {} ok | {} erro.",
                                resultado.registrosTotais(),
                                resultado.arquivosSucesso(),
                                resultado.arquivosComErro());
                    }
            );

            if (resumo.isEmpty()) {
                log.info("[SFTP] Nenhum arquivo novo encontrado.");
            } else {
                log.info("[SFTP] Ciclo principal: {} /recebidos | {} /radar.",
                        resumo.recebidos(), resumo.radar());
            }

        } catch (Exception e) {
            log.error("[SFTP] Erro crítico no ciclo: ", e);
        } finally {
            log.info("[SFTP] ════ Ciclo concluído em {}ms ════",
                    System.currentTimeMillis() - inicio);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CICLO DE RETRY — executa separado, a cada 30 minutos
    // ─────────────────────────────────────────────────────────────

    /**
     * Retenta arquivos que falharam em ciclos anteriores.
     * Roda a cada 30 minutos e é independente do ciclo principal.
     */
    @Scheduled(fixedRateString = "${sftp.retry.rate.ms:1800000}")
    public void executarRetry() {
        List<ArquivoSftpProcessado> elegiveis = downloader.listarElegiveisParaRetry();

        if (elegiveis.isEmpty()) {
            log.debug("[SFTP-Retry] Nenhum arquivo elegível para retry.");
            return;
        }

        log.info("[SFTP-Retry] Iniciando retry de {} arquivo(s) com erro.", elegiveis.size());
        long inicio = System.currentTimeMillis();

        FileProcessor.ResultadoLote resultado = fileProcessor.retentarComErro(elegiveis);

        log.info("[SFTP-Retry] Concluído em {}ms — {} registros | {} ok | {} erro.",
                System.currentTimeMillis() - inicio,
                resultado.registrosTotais(),
                resultado.arquivosSucesso(),
                resultado.arquivosComErro());
    }
}
