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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

    // ─── Configurações ───────────────────────────────────────────

    @Value("${sftp.remote.directory:/recebidos}")
    private String remoteDirRecebidos;

    @Value("${sftp.remote.radar.directory:/radar}")
    private String remoteDirRadar;

    @Value("${sftp.local.directory}")
    private String localBaseDirectory;

    @Value("${sftp.download.limite.dias:1}")
    private int limiteDias;

    /** Quantos arquivos baixar por lote antes de devolver ao orquestrador para processamento. */
    @Value("${sftp.download.batch.size:50}")
    private int batchSize;

    /** Máximo de tentativas antes de abandonar um arquivo com erro. */
    @Value("${sftp.max.tentativas:3}")
    private int maxTentativas;

    // ─── Dependências ────────────────────────────────────────────

    private final ArquivoSftpProcessadoRepository arquivoRepo;

    // ─── Patterns ────────────────────────────────────────────────

    private static final Pattern PATTERN_ISO_T           = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})T");
    private static final Pattern PATTERN_APOS_UNDERSCORE = Pattern.compile("_(\\d{8})");
    private static final Pattern PATTERN_PASTA_DATA      = Pattern.compile("^(\\d{8})$");
    private static final DateTimeFormatter FMT_YYYYMMDD  = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ─────────────────────────────────────────────────────────────
    // API PÚBLICA
    // ─────────────────────────────────────────────────────────────

    /**
     * Descobre arquivos novos no SFTP e os entrega ao {@code consumer} em lotes.
     *
     * <p>O consumer (normalmente o {@link SftpOrchestrator}) processa cada lote
     * imediatamente — sem acumular todos os arquivos em memória.
     *
     * @param sftp     Canal SFTP aberto.
     * @param consumer Callback chamado a cada lote; recebe o lote e o TipoFonte.
     * @return Resumo total de arquivos baixados (somando todos os lotes).
     */
    public DownloadSummary baixarEmLotes(ChannelSftp sftp, BatchConsumer consumer)
            throws IOException {

        Path localRecebidos = Path.of(localBaseDirectory, "recebidos");
        Path localRadar     = Path.of(localBaseDirectory, "radar");
        Files.createDirectories(localRecebidos);
        Files.createDirectories(localRadar);

        LocalDate dataLimite = LocalDate.now().minusDays(limiteDias);
        log.info("[SFTP] Limite: {} dia(s) → aceitando arquivos a partir de {}.", limiteDias, dataLimite);

        // Carrega nomes já conhecidos do banco UMA VEZ para evitar N selects na listagem
        Set<String> jaConhecidosRecebidos = arquivoRepo.findNomesByTipoFonte(TipoFonte.RECEBIDOS);
        Set<String> jaConhecidosRadar     = arquivoRepo.findNomesByTipoFonte(TipoFonte.RADAR);

        log.info("[SFTP] Já conhecidos no banco: {} /recebidos | {} /radar.",
                jaConhecidosRecebidos.size(), jaConhecidosRadar.size());

        int totalRecebidos = processarPastaEmLotes(
                sftp, remoteDirRecebidos, localRecebidos,
                TipoFonte.RECEBIDOS, jaConhecidosRecebidos, dataLimite, consumer);

        int totalRadar = processarPastaEmLotes(
                sftp, remoteDirRadar, localRadar,
                TipoFonte.RADAR, jaConhecidosRadar, dataLimite, consumer);

        return new DownloadSummary(totalRecebidos, totalRadar);
    }

    // ─────────────────────────────────────────────────────────────
    // PROCESSAMENTO POR PASTA (com suporte a subpastas recursivas)
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private int processarPastaEmLotes(
            ChannelSftp sftp,
            String remotePath,
            Path localPath,
            TipoFonte tipoFonte,
            Set<String> jaConhecidos,
            LocalDate dataLimite,
            BatchConsumer consumer) {

        int totalBaixados = 0;
        List<Path> loteAtual = new ArrayList<>(batchSize);

        try {
            sftp.cd(remotePath);
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");
            log.info("[SFTP] Varrendo '{}': {} entrada(s).", remotePath, entries.size());

            for (ChannelSftp.LsEntry entry : entries) {
                String nome = entry.getFilename();
                if (nome.startsWith(".")) continue;

                if (entry.getAttrs().isDir()) {
                    // Subpastas: filtra por data (apenas /radar tem subpastas yyyyMMdd)
                    if (pastaForaDoPeriodo(nome, dataLimite)) {
                        log.debug("[SFTP] Subpasta fora do período, pulando: {}/{}", remotePath, nome);
                        continue;
                    }
                    Path subLocal = localPath.resolve(nome);
                    Files.createDirectories(subLocal);
                    totalBaixados += processarPastaEmLotes(
                            sftp, remotePath + "/" + nome, subLocal,
                            tipoFonte, jaConhecidos, dataLimite, consumer);
                    sftp.cd(remotePath);
                    continue;
                }

                // ── Arquivo folha ────────────────────────────────────────
                if (jaConhecidos.contains(nome)) {
                    log.debug("[SFTP] Já conhecido no banco, ignorando: {}", nome);
                    continue;
                }
                if (!isDentroDoPeriodo(nome, dataLimite)) {
                    log.debug("[SFTP] Arquivo fora do período: {}", nome);
                    continue;
                }

                Optional<Path> baixado = baixarArquivo(sftp, nome, localPath, tipoFonte, jaConhecidos);
                baixado.ifPresent(p -> {
                    loteAtual.add(p);
                    log.debug("[SFTP] Adicionado ao lote: {} ({}/{})", nome, loteAtual.size(), batchSize);
                });

                // Quando o lote atinge o tamanho configurado, entrega para processamento
                if (loteAtual.size() >= batchSize) {
                    log.info("[SFTP] Lote de {} arquivo(s) pronto para processamento ({}).",
                            loteAtual.size(), tipoFonte);
                    consumer.processar(new ArrayList<>(loteAtual), tipoFonte);
                    totalBaixados += loteAtual.size();
                    loteAtual.clear();
                }
            }
        } catch (SftpException e) {
            log.error("[SFTP] Erro ao varrer '{}': {}", remotePath, e.getMessage());
        } catch (IOException e) {
            log.error("[SFTP] Erro de I/O em '{}': {}", remotePath, e.getMessage());
        }

        // Processa o lote residual (último lote, menor que batchSize)
        if (!loteAtual.isEmpty()) {
            log.info("[SFTP] Lote residual de {} arquivo(s) ({}).", loteAtual.size(), tipoFonte);
            consumer.processar(new ArrayList<>(loteAtual), tipoFonte);
            totalBaixados += loteAtual.size();
            loteAtual.clear();
        }

        return totalBaixados;
    }

    // ─────────────────────────────────────────────────────────────
    // DOWNLOAD + REGISTRO NO BANCO
    // ─────────────────────────────────────────────────────────────

    /**
     * Baixa um arquivo e persiste um registro {@code BAIXADO} no banco.
     * O status será atualizado pelo orquestrador após o processamento.
     */
    @Transactional
    private Optional<Path> baixarArquivo(
            ChannelSftp sftp,
            String nomeRemoto,
            Path destino,
            TipoFonte tipoFonte,
            Set<String> jaConhecidos) {

        Path alvo = destino.resolve(nomeRemoto);
        try (InputStream is = sftp.get(nomeRemoto)) {
            Files.copy(is, alvo, StandardCopyOption.REPLACE_EXISTING);

            // Persiste o registro no banco (status BAIXADO)
            ArquivoSftpProcessado registro = ArquivoSftpProcessado.builder()
                    .nomeArquivo(nomeRemoto)
                    .tipoFonte(tipoFonte)
                    .status(StatusProcessamento.BAIXADO)
                    .baixadoEm(LocalDateTime.now())
                    .tentativas(0)
                    .build();
            arquivoRepo.save(registro);

            // Atualiza o set in-memory para não tentar baixar novamente no mesmo ciclo
            jaConhecidos.add(nomeRemoto);

            log.info("[SFTP] ✅ Baixado e registrado: {}", nomeRemoto);
            return Optional.of(alvo);

        } catch (Exception e) {
            log.error("[SFTP] ❌ Falha ao baixar '{}': {}", nomeRemoto, e.getMessage());

            // Registra a falha no banco para auditoria
            try {
                ArquivoSftpProcessado falha = ArquivoSftpProcessado.builder()
                        .nomeArquivo(nomeRemoto)
                        .tipoFonte(tipoFonte)
                        .status(StatusProcessamento.ERRO)
                        .baixadoEm(LocalDateTime.now())
                        .mensagemErro("Falha no download: " + e.getMessage())
                        .tentativas(1)
                        .build();
                arquivoRepo.save(falha);
                jaConhecidos.add(nomeRemoto); // evita retry infinito no mesmo ciclo
            } catch (Exception ex) {
                log.warn("[SFTP] Não foi possível registrar falha no banco para '{}': {}", nomeRemoto, ex.getMessage());
            }
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RETRY DE ARQUIVOS COM ERRO
    // ─────────────────────────────────────────────────────────────

    /**
     * Retorna arquivos que falharam em ciclos anteriores e ainda têm tentativas disponíveis.
     * Chamado pelo {@link SftpOrchestrator} em ciclos separados para não bloquear novos downloads.
     */
    public List<ArquivoSftpProcessado> listarElegiveisParaRetry() {
        return arquivoRepo.findByStatusAndTentativasLessThan(StatusProcessamento.ERRO, maxTentativas);
    }

    // ─────────────────────────────────────────────────────────────
    // FILTROS
    // ─────────────────────────────────────────────────────────────

    private boolean pastaForaDoPeriodo(String nomePasta, LocalDate dataLimite) {
        Matcher m = PATTERN_PASTA_DATA.matcher(nomePasta);
        if (!m.matches()) return false;
        try {
            LocalDate dataPasta = LocalDate.parse(nomePasta, FMT_YYYYMMDD);
            return dataPasta.isBefore(dataLimite);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDentroDoPeriodo(String nomeArquivo, LocalDate dataLimite) {
        return extrairDataDoNome(nomeArquivo)
                .map(d -> !d.isBefore(dataLimite))
                .orElse(true); // sem data no nome → baixar por segurança
    }

    private Optional<LocalDate> extrairDataDoNome(String nome) {
        Matcher m1 = PATTERN_ISO_T.matcher(nome);
        if (m1.find()) {
            try { return Optional.of(LocalDate.parse(m1.group(1))); } catch (Exception ignored) {}
        }
        Matcher m2 = PATTERN_APOS_UNDERSCORE.matcher(nome);
        if (m2.find()) {
            try { return Optional.of(LocalDate.parse(m2.group(1), FMT_YYYYMMDD)); } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    // ─────────────────────────────────────────────────────────────
    // TIPOS AUXILIARES
    // ─────────────────────────────────────────────────────────────

    /**
     * Callback para o orquestrador receber e processar cada lote.
     */
    @FunctionalInterface
    public interface BatchConsumer {
        void processar(List<Path> lote, TipoFonte tipoFonte);
    }

    /**
     * Resumo ao final do ciclo completo.
     */
    public record DownloadSummary(int recebidos, int radar) {
        public int total() { return recebidos + radar; }
        public boolean isEmpty() { return total() == 0; }

        @Override
        public String toString() {
            return String.format("DownloadSummary[recebidos=%d, radar=%d, total=%d]",
                    recebidos, radar, total());
        }
    }
}