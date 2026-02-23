package com.coruja.services.sftp;

import com.coruja.enuns.TipoFonte;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Orquestra o ciclo completo SFTP:
 * <ol>
 *   <li>Abre conexão.</li>
 *   <li>Baixa arquivos novos de {@code /recebidos} e {@code /radar}.</li>
 *   <li>Processa cada conjunto com o {@link TipoFonte} correto.</li>
 *   <li>Fecha conexão de forma segura.</li>
 * </ol>
 *
 * <p>Responsabilidade única: coordenação. Nenhuma lógica de parse ou download aqui.
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
        log.info(">>> SftpOrchestrator (Eixo) inicializado. Primeiro ciclo em breve.");
    }

    @Scheduled(fixedRateString = "${sftp.schedule.rate.ms}")
    public void executarCiclo() {
        log.info("[SFTP] ── Início do ciclo ──");
        long inicio = System.currentTimeMillis();

        try (SftpConnectionManager.SftpConnection conn = connectionManager.open()) {

            SftpDownloader.DownloadResult resultado = downloader.baixarArquivosNovos(conn.channel());

            if (resultado.isEmpty()) {
                log.info("[SFTP] Nenhum arquivo novo encontrado.");
                return;
            }

            // Processa /recebidos (sem KM)
            fileProcessor.processar(resultado.recebidos(), TipoFonte.RECEBIDOS);

            // Processa /radar (com KM)
            fileProcessor.processar(resultado.radar(), TipoFonte.RADAR);

        } catch (Exception e) {
            log.error("[SFTP] Erro crítico no ciclo: ", e);
        } finally {
            log.info("[SFTP] ── Ciclo concluído em {}ms ──", System.currentTimeMillis() - inicio);
        }
    }
}
