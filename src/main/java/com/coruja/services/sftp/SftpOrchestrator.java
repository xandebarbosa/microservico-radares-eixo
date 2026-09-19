package com.coruja.services.sftp;

import com.coruja.entities.ArquivoSftpProcessado;
import com.coruja.exceptions.SftpUnavailableException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orquestra o ciclo completo SFTP com <b>processamento em lotes assíncronos e paralelos</b>.
 *
 * <h3>Fluxo otimizado:</h3>
 * <pre>
 *   1. Aciona o Downloader (que utiliza Connection Pool e gerencia sua própria concorrência de I/O)
 *   2. Recebe lotes de arquivos recém-baixados via callback (BatchConsumer)
 *   3. Despacha o processamento de cada lote para threads em background, liberando o orquestrador
 *   4. Em caso de instabilidade na rede (Circuit Breaker aberto), aborta graciosamente
 *   5. Em ciclo independente, retenta o processamento de arquivos com falha
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SftpOrchestrator {

    private final SftpDownloader downloader;
    private final FileProcessor fileProcessor;

    @Value("${sftp.schedule.rate.ms:300000}")
    private long scheduleRateMs;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    // Semáforo atômico para evitar que dois ciclos de varredura rodem ao mesmo tempo
    private final AtomicBoolean isVarreduraEmAndamento = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        log.info(">>> SftpOrchestrator (Eixo) inicializado. Execução não-bloqueante a cada {} ms.", scheduleRateMs);
    }

    @Scheduled(fixedDelayString = "${sftp.schedule.rate.ms:300000}")
    public void executarCiclo() {
        // Se a varredura anterior demorou mais de 5 minutos, pula o ciclo atual
        if (!isVarreduraEmAndamento.compareAndSet(false, true)) {
            log.warn("[SFTP] O ciclo anterior ainda está varrendo/baixando arquivos. Pulando ciclo atual.");
            return;
        }

        log.info("[SFTP] ════ Início do ciclo principal (Gatilho) ════");
        long inicio = System.currentTimeMillis();

        // Joga TODO o peso da rede e varredura para uma thread secundária.
        // Assim o Orquestrador fica livre e imprime o log do próximo ciclo imediatamente.
        CompletableFuture.runAsync(() -> {
            try {
                downloader.baixarArquivos((lote, tipoFonte) -> {
                    log.info("[SFTP] Lote de {} arquivo(s) [{}] recebido para processamento.", lote.size(), tipoFonte);

                    CompletableFuture.runAsync(() -> {
                        FileProcessor.ResultadoLote resultado = fileProcessor.processar(lote, tipoFonte);
                        log.info("[SFTP] Lote processado: {} registros | {} ok | {} erro.",
                                resultado.registrosTotais(),
                                resultado.arquivosSucesso(),
                                resultado.arquivosComErro());
                    }).exceptionally(ex -> {
                        log.error("[SFTP-Async] Falha não tratada no processamento em lote.", ex);
                        return null;
                    });
                });

            } catch (SftpUnavailableException e) {
                log.warn("[SFTP-CircuitBreaker] Ciclo interrompido. {}", e.getMessage());
            } catch (Exception e) {
                log.error("[SFTP] Erro crítico na thread de varredura: ", e);
            } finally {
                isVarreduraEmAndamento.set(false); // Libera o semáforo para o próximo ciclo
            }
        });

        // Este log agora será impresso na hora, com a precisão dos 5 minutos exatos
        LocalDateTime proximaExecucao = LocalDateTime.now().plus(scheduleRateMs, ChronoUnit.MILLIS);
        log.info("[SFTP] ════ Gatilho disparado em {}ms. Próxima varredura programada para: {} ════",
                System.currentTimeMillis() - inicio,
                proximaExecucao.format(FORMATTER));
    }

    @Scheduled(fixedDelayString = "${sftp.retry.rate.ms:1800000}")
    public void executarRetry() {
        List<ArquivoSftpProcessado> elegiveis = downloader.listarElegiveisParaRetry();

        if (elegiveis == null || elegiveis.isEmpty()) {
            return;
        }

        log.info("[SFTP-Retry] Iniciando retry de {} arquivo(s) com erro.", elegiveis.size());
        long inicio = System.currentTimeMillis();

        try {
            FileProcessor.ResultadoLote resultado = fileProcessor.retentarComErro(elegiveis);

            log.info("[SFTP-Retry] Concluído em {}ms — {} registros | {} ok | {} erro.",
                    System.currentTimeMillis() - inicio,
                    resultado.registrosTotais(),
                    resultado.arquivosSucesso(),
                    resultado.arquivosComErro());
        } catch (Exception e) {
            log.error("[SFTP-Retry] Falha inesperada durante a execução da rotina de retentativa.", e);
        }
    }
}
