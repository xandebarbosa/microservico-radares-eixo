package com.coruja.services.sftp;

import com.coruja.entities.ArquivoSftpProcessado;
import com.coruja.enuns.StatusProcessamento;
import com.coruja.enuns.TipoFonte;
import com.coruja.repositories.ArquivoSftpProcessadoRepository;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Baixa arquivos SFTP em <b>lotes configuráveis</b>, registrando cada arquivo no banco
 * de dados antes e depois do download.
 *
 * <h3>Mudanças em relação à versão anterior:</h3>
 * <ul>
 *   <li>Controle de duplicatas migrado de sets in-memory para {@link ArquivoSftpProcessadoRepository}
 *       → sobrevive a restarts do container.</li>
 *   <li>Downloads ocorrem em lotes ({@code sftp.download.batch.size}, padrão 50 arquivos).
 *       Cada lote é retornado ao {@link SftpOrchestrator} para processamento imediato,
 *       liberando memória antes do próximo lote.</li>
 *   <li>Cada arquivo é persistido com status {@code BAIXADO} assim que chega no disco,
 *       e atualizado para {@code PROCESSADO} ou {@code ERRO} pelo orquestrador.</li>
 *   <li>Arquivos com status {@code ERRO} e menos de {@code sftp.max.tentativas} (padrão 3)
 *       são elegíveis para reprocessamento.</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SftpDownloader {

    private final ArquivoSftpProcessadoRepository arquivoRepo;
    private final SftpConnectionManager connectionManager;

    @Value("${sftp.remote.directory:/recebidos}")
    private String remoteDirRecebidos;

    @Value("${sftp.remote.radar.directory:/radar}")
    private String remoteDirRadar;

    @Value("${sftp.local.directory}")
    private String localBaseDirectory;

    @Value("${sftp.max.tentativas:3}")
    private int maxTentativas;

    @Value("${sftp.download.limite.dias:90}")
    private int limiteDias;

    private final ExecutorService sftpExecutor = Executors.newFixedThreadPool(15);

    public void baixarArquivos(BatchConsumer consumer) {
        long limiteEpoch = Instant.now().minus(limiteDias, ChronoUnit.DAYS).getEpochSecond();

        try {
            processarDiretorioContinuo(remoteDirRecebidos, TipoFonte.RECEBIDOS,
                    Path.of(localBaseDirectory, "recebidos"), limiteEpoch, consumer);

            processarDiretorioContinuo(remoteDirRadar, TipoFonte.RADAR,
                    Path.of(localBaseDirectory, "radar"), limiteEpoch, consumer);

        } catch (Exception e) {
            log.error("[SFTP] Erro crítico na varredura contínua dos diretórios remotos.", e);
        }
    }

    /**
     * Varre pastas e DISPARA o download imediatamente, sem esperar montar lotes inteiros.
     */
    private void processarDiretorioContinuo(String remoteBaseDir, TipoFonte tipo,
                                            Path localBaseDir, long limiteEpoch, BatchConsumer consumer) {

        Set<String> conhecidos = arquivoRepo.findNomesByTipoFonte(tipo);
        log.info("[SFTP] Iniciando varredura contínua em '{}'...", remoteBaseDir);

        Queue<DirToScan> dirsToScan = new ConcurrentLinkedQueue<>();
        dirsToScan.add(new DirToScan(remoteBaseDir, localBaseDir));

        // Lista de CompletableFutures para rastrear todos os downloads ativos
        List<CompletableFuture<Void>> todosDownloads = Collections.synchronizedList(new ArrayList<>());

        while (!dirsToScan.isEmpty()) {
            List<CompletableFuture<Void>> folderScanFutures = new ArrayList<>();
            List<DirToScan> currentLevel = new ArrayList<>();
            while (!dirsToScan.isEmpty()) currentLevel.add(dirsToScan.poll());

            for (DirToScan dir : currentLevel) {
                folderScanFutures.add(CompletableFuture.runAsync(() -> {
                    try (SftpConnectionManager.SftpConnection conn = connectionManager.borrowConnection()) {
                        Files.createDirectories(dir.local());

                        @SuppressWarnings("unchecked")
                        Vector<ChannelSftp.LsEntry> entries = conn.channel().ls(dir.remote());

                        for (ChannelSftp.LsEntry entry : entries) {
                            String nome = entry.getFilename();
                            if (nome.equals(".") || nome.equals("..") || nome.equalsIgnoreCase("Processados")) continue;

                            String fullRemote = dir.remote() + "/" + nome;
                            Path fullLocal = dir.local().resolve(nome);
                            long mTime = entry.getAttrs().getMTime();

                            if (entry.getAttrs().isDir()) {
                                boolean isRaiz = remoteDirRadar.equals(fullRemote) || remoteDirRecebidos.equals(fullRemote);
                                if (isRaiz || mTime >= limiteEpoch) {
                                    dirsToScan.add(new DirToScan(fullRemote, fullLocal));
                                }
                            }
                            else if (nome.toLowerCase().endsWith(".csv") && mTime >= limiteEpoch) {
                                if (!conhecidos.contains(nome)) {
                                    // 🟢 DISPARO IMEDIATO: Encontrou? Baixa e processa agora!
                                    ArquivoPendente pendente = new ArquivoPendente(fullRemote, fullLocal, nome, tipo);
                                    todosDownloads.add(
                                            CompletableFuture.supplyAsync(() -> baixarUnico(pendente), sftpExecutor)
                                                    .thenAccept(pathOpt -> pathOpt.ifPresent(path -> {
                                                        // Envia o arquivo sozinho (lote de 1) para o consumer assim que acabar
                                                        consumer.processar(List.of(path), tipo);
                                                    }))
                                    );
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[SFTP] Falha ao listar diretório {}: {}", dir.remote(), e.getMessage());
                    }
                }, sftpExecutor));
            }
            // Espera terminar de listar o nível atual de pastas antes de descer pro próximo nível
            CompletableFuture.allOf(folderScanFutures.toArray(new CompletableFuture[0])).join();
        }

        // 🟢 Aguarda todos os downloads disparados durante a varredura terminarem
        CompletableFuture.allOf(todosDownloads.toArray(new CompletableFuture[0])).join();
        log.info("[SFTP] Ciclo de '{}' 100% finalizado (Varredura + Downloads).", remoteBaseDir);
    }

    private Optional<Path> baixarUnico(ArquivoPendente pendente) {
        log.info("[SFTP] ⬇️ Baixando: {}", pendente.nomeArquivo());

        try (SftpConnectionManager.SftpConnection conn = connectionManager.borrowConnection();
             InputStream is = conn.channel().get(pendente.remoteFullPath())) {

            Files.copy(is, pendente.localFullPath(), StandardCopyOption.REPLACE_EXISTING);

            ArquivoSftpProcessado registro = arquivoRepo.findByNomeArquivo(pendente.nomeArquivo())
                    .orElseGet(() -> ArquivoSftpProcessado.builder()
                            .nomeArquivo(pendente.nomeArquivo())
                            .tipoFonte(pendente.tipo())
                            .build());

            registro.setStatus(StatusProcessamento.BAIXADO);
            registro.setBaixadoEm(LocalDateTime.now());
            registro.setTentativas(0);

            // Salva individualmente no banco (já que não temos mais lotes amarrados à varredura)
            arquivoRepo.save(registro);

            log.info("[SFTP] ✅ Concluído: {}", pendente.nomeArquivo());
            return Optional.of(pendente.localFullPath());

        } catch (Exception e) {
            log.error("[SFTP] ❌ Erro ao baixar '{}': {}", pendente.nomeArquivo(), e.getMessage());
            return Optional.empty();
        }
    }

    public List<ArquivoSftpProcessado> listarElegiveisParaRetry() {
        return arquivoRepo.findByStatusAndTentativasLessThan(StatusProcessamento.ERRO, maxTentativas);
    }

    @FunctionalInterface
    public interface BatchConsumer {
        void processar(List<Path> lote, TipoFonte tipoFonte);
    }

    private record ArquivoPendente(String remoteFullPath, Path localFullPath, String nomeArquivo, TipoFonte tipo) {}
    private record DirToScan(String remote, Path local) {}
}