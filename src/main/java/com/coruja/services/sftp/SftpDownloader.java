package com.coruja.services.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Responsável exclusivamente pelo download de arquivos via SFTP.
 *
 * <h3>Estratégia anti-duplicação (dois níveis):</h3>
 * <ol>
 *   <li><b>Em memória entre ciclos:</b> sets {@code arquivosBaixadosRecebidos} e
 *       {@code arquivosBaixadosRadar} são campos da classe e acumulam os nomes de
 *       todos os arquivos já baixados desde que o container subiu. Na primeira
 *       execução são pré-carregados com o conteúdo do disco (para sobreviver a
 *       restarts com volume persistido).</li>
 *   <li><b>Em disco:</b> antes de baixar, verifica se o arquivo já existe
 *       localmente via {@link Files#exists}.</li>
 * </ol>
 *
 * <h3>Filtro de subpastas (/radar):</h3>
 * As subpastas de /radar têm nome no formato yyyyMMdd (ex: 20260223).
 * O código filtra as subpastas pelo nome ANTES de entrar nelas, evitando
 * varrer centenas de subpastas antigas desnecessariamente.
 *
 * <h3>Formatos de nome de arquivo suportados:</h3>
 * <ul>
 *   <li>{@code EIXOSP_2026-02-23T14-38-176641.csv} → extrai yyyy-MM-dd antes do T</li>
 *   <li>{@code EIXOSP_20260223142509281.csv}        → extrai 8 dígitos após o _</li>
 * </ul>
 */
@Component
@Slf4j
public class SftpDownloader {

    @Value("${sftp.remote.directory:/recebidos}")
    private String remoteDirRecebidos;

    @Value("${sftp.remote.radar.directory:/radar}")
    private String remoteDirRadar;

    @Value("${sftp.local.directory}")
    private String localBaseDirectory;

    /**
     * Janela retroativa em dias. Padrão: 1 (hoje e ontem).
     * Configurável via {@code sftp.download.limite.dias}.
     */
    @Value("${sftp.download.limite.dias:1}")
    private int limiteDias;

    /**
     * Cache em memória de arquivos já baixados — persiste entre ciclos do scheduler
     * enquanto o container estiver no ar. Evita redownload mesmo que o disco seja
     * efêmero (container sem volume).
     */
    private final Set<String> arquivosBaixadosRecebidos = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> arquivosBaixadosRadar     = Collections.synchronizedSet(new HashSet<>());
    private boolean cacheInicializado = false;

    // Formato 1: yyyy-MM-ddT  →  EIXOSP_2026-02-23T14-38-176641.csv
    private static final Pattern PATTERN_ISO_T          = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})T");
    // Formato 2: _yyyyMMdd    →  EIXOSP_20260223142509281.csv
    private static final Pattern PATTERN_APOS_UNDERSCORE = Pattern.compile("_(\\d{8})");
    // Pasta com nome yyyyMMdd →  20260223  (subpastas de /radar)
    private static final Pattern PATTERN_PASTA_DATA     = Pattern.compile("^(\\d{8})$");

    private static final DateTimeFormatter FMT_YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ─────────────────────────────────────────────────────────────
    // API PÚBLICA
    // ─────────────────────────────────────────────────────────────

    public DownloadResult baixarArquivosNovos(ChannelSftp sftp) throws IOException {
        Path localRecebidos = Path.of(localBaseDirectory, "recebidos");
        Path localRadar     = Path.of(localBaseDirectory, "radar");
        Files.createDirectories(localRecebidos);
        Files.createDirectories(localRadar);

        // Na primeira execução, pré-carrega o cache com arquivos já presentes no disco
        // (útil quando há volume Docker persistido — evita redownload após restart)
        if (!cacheInicializado) {
            arquivosBaixadosRecebidos.addAll(listarArquivosLocais(localRecebidos));
            arquivosBaixadosRadar.addAll(listarArquivosLocaisRecursivo(localRadar));
            cacheInicializado = true;
            log.info("[SFTP] Cache inicializado: {} já conhecido(s) em /recebidos | {} em /radar.",
                    arquivosBaixadosRecebidos.size(), arquivosBaixadosRadar.size());
        }

        LocalDate dataLimite = LocalDate.now().minusDays(limiteDias);
        log.info("[SFTP] Limite: {} dia(s) → aceitando arquivos/pastas a partir de {}.", limiteDias, dataLimite);
        log.info("[SFTP] Cache atual: {} conhecido(s) em /recebidos | {} em /radar.",
                arquivosBaixadosRecebidos.size(), arquivosBaixadosRadar.size());

        List<Path> recebidos = baixarPastaRecebidos(sftp, localRecebidos, arquivosBaixadosRecebidos, dataLimite);
        List<Path> radar     = baixarPastaRadar(sftp, remoteDirRadar, localRadar, arquivosBaixadosRadar, dataLimite);

        log.info("[SFTP] Concluído: {} novo(s) de /recebidos | {} novo(s) de /radar.",
                recebidos.size(), radar.size());

        return new DownloadResult(recebidos, radar);
    }

    // ─────────────────────────────────────────────────────────────
    // /recebidos  — sem subpastas, filtra por data no nome do arquivo
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Path> baixarPastaRecebidos(
            ChannelSftp sftp,
            Path localPath,
            Set<String> locais,
            LocalDate dataLimite) {

        List<Path> baixados = new ArrayList<>();
        try {
            sftp.cd(remoteDirRecebidos);
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");
            log.info("[SFTP] Varrendo '{}': {} entrada(s).", remoteDirRecebidos, entries.size());

            for (ChannelSftp.LsEntry entry : entries) {
                String nome = entry.getFilename();
                if (nome.startsWith(".") || entry.getAttrs().isDir()) continue;

                if (locais.contains(nome)) {
                    log.debug("[SFTP] Já baixado, ignorando: {}", nome);
                    continue;
                }
                if (!isDentroDoPeriodo(nome, dataLimite)) {
                    log.debug("[SFTP] Fora do período ({}): {}", dataLimite, nome);
                    continue;
                }

                baixarArquivo(sftp, nome, localPath, locais).ifPresent(baixados::add);
            }
        } catch (SftpException e) {
            log.error("[SFTP] Erro ao varrer '{}': {}", remoteDirRecebidos, e.getMessage());
        }
        return baixados;
    }

    // ─────────────────────────────────────────────────────────────
    // /radar  — subpastas filtradas por data no NOME DA PASTA
    //           arquivos filtrados por data no nome do arquivo
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Path> baixarPastaRadar(
            ChannelSftp sftp,
            String remotePath,
            Path localPath,
            Set<String> locais,
            LocalDate dataLimite) {

        List<Path> baixados = new ArrayList<>();
        try {
            sftp.cd(remotePath);
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");
            log.info("[SFTP] Varrendo '{}': {} entrada(s).", remotePath, entries.size());

            for (ChannelSftp.LsEntry entry : entries) {
                String nome = entry.getFilename();
                if (nome.startsWith(".")) continue;

                if (entry.getAttrs().isDir()) {
                    // Se o nome da pasta for yyyyMMdd, filtra por data antes de entrar
                    if (pastaForaDoPeriodo(nome, dataLimite)) {
                        log.debug("[SFTP] Subpasta fora do período, pulando: {}/{}", remotePath, nome);
                        continue;
                    }
                    // Entra recursivamente na subpasta
                    Path subLocal = localPath.resolve(nome);
                    Files.createDirectories(subLocal);
                    // Passa o mesmo set global (arquivosBaixadosRadar) para que
                    // arquivos baixados em qualquer subpasta bloqueiem redownload em outras
                    baixados.addAll(
                            baixarPastaRadar(sftp, remotePath + "/" + nome,
                                    subLocal, locais, dataLimite)
                    );
                    sftp.cd(remotePath);
                    continue;
                }

                // Arquivo folha
                if (locais.contains(nome)) {
                    log.debug("[SFTP] Já baixado, ignorando: {}", nome);
                    continue;
                }
                if (!isDentroDoPeriodo(nome, dataLimite)) {
                    log.debug("[SFTP] Arquivo fora do período ({}): {}", dataLimite, nome);
                    continue;
                }

                baixarArquivo(sftp, nome, localPath, locais).ifPresent(baixados::add);
            }
        } catch (SftpException e) {
            log.error("[SFTP] Erro ao varrer '{}': {}", remotePath, e.getMessage());
        } catch (IOException e) {
            log.error("[SFTP] Erro de I/O em '{}': {}", remotePath, e.getMessage());
        }
        return baixados;
    }

    // ─────────────────────────────────────────────────────────────
    // DOWNLOAD
    // ─────────────────────────────────────────────────────────────

    /**
     * Baixa um arquivo e, se bem-sucedido, adiciona o nome ao set de locais
     * para evitar redownload na mesma execução do ciclo.
     */
    private Optional<Path> baixarArquivo(ChannelSftp sftp, String nomeRemoto,
                                         Path destino, Set<String> locais) {
        Path alvo = destino.resolve(nomeRemoto);
        try (InputStream is = sftp.get(nomeRemoto)) {
            Files.copy(is, alvo, StandardCopyOption.REPLACE_EXISTING);
            locais.add(nomeRemoto); // atualiza o set em memória
            log.info("[SFTP] ✅ Baixado: {}", nomeRemoto);
            return Optional.of(alvo);
        } catch (Exception e) {
            log.error("[SFTP] ❌ Falha ao baixar '{}': {}", nomeRemoto, e.getMessage());
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // FILTROS
    // ─────────────────────────────────────────────────────────────

    /**
     * Retorna true se a PASTA deve ser PULADA.
     * Só filtra pastas com nome exatamente no formato yyyyMMdd.
     * Pastas com outros nomes (ex: "FSCII6568") são sempre visitadas.
     */
    private boolean pastaForaDoPeriodo(String nomePasta, LocalDate dataLimite) {
        Matcher m = PATTERN_PASTA_DATA.matcher(nomePasta);
        if (!m.matches()) return false; // nome não é data → não pular
        try {
            LocalDate dataPasta = LocalDate.parse(nomePasta, FMT_YYYYMMDD);
            return dataPasta.isBefore(dataLimite);
        } catch (Exception e) {
            return false; // parse falhou → não pular por segurança
        }
    }

    /**
     * Retorna true se o arquivo deve ser baixado.
     * Arquivos sem data reconhecível no nome são sempre baixados.
     */
    private boolean isDentroDoPeriodo(String nomeArquivo, LocalDate dataLimite) {
        return extrairDataDoNome(nomeArquivo)
                .map(d -> !d.isBefore(dataLimite))
                .orElseGet(() -> {
                    log.debug("[SFTP] Sem data no nome, baixando por segurança: {}", nomeArquivo);
                    return true;
                });
    }

    private Optional<LocalDate> extrairDataDoNome(String nome) {
        // Formato 1: yyyy-MM-ddT
        Matcher m1 = PATTERN_ISO_T.matcher(nome);
        if (m1.find()) {
            try { return Optional.of(LocalDate.parse(m1.group(1))); } catch (Exception ignored) {}
        }
        // Formato 2: _yyyyMMdd (8 dígitos após o underscore)
        Matcher m2 = PATTERN_APOS_UNDERSCORE.matcher(nome);
        if (m2.find()) {
            try {
                return Optional.of(LocalDate.parse(m2.group(1), FMT_YYYYMMDD));
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    // ─────────────────────────────────────────────────────────────
    // AUXILIARES
    // ─────────────────────────────────────────────────────────────

    private Set<String> listarArquivosLocais(Path dir) {
        if (!Files.exists(dir)) return new HashSet<>();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (IOException e) {
            log.warn("[SFTP] Não foi possível listar '{}': {}", dir, e.getMessage());
            return new HashSet<>();
        }
    }

    /**
     * Lista recursivamente todos os arquivos dentro de um diretório (incluindo subpastas).
     * Usado para pré-carregar o cache do /radar na inicialização.
     */
    private Set<String> listarArquivosLocaisRecursivo(Path dir) {
        if (!Files.exists(dir)) return new HashSet<>();
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (IOException e) {
            log.warn("[SFTP] Não foi possível listar recursivamente '{}': {}", dir, e.getMessage());
            return new HashSet<>();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RESULTADO
    // ─────────────────────────────────────────────────────────────

    public record DownloadResult(List<Path> recebidos, List<Path> radar) {
        public boolean isEmpty() {
            return recebidos.isEmpty() && radar.isEmpty();
        }
    }
}