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
 * <h3>Comportamento por pasta:</h3>
 * <ul>
 *   <li><b>/recebidos</b> — NÃO filtra por data no nome.
 *       Arquivos como "P1 - Rio Claro.txt" (sem data) são sempre baixados.
 *       Salvos em: {@code sftp.local.directory/recebidos/}</li>
 *   <li><b>/radar</b> — Filtra por data (>= ontem) + recursivo em sub-pastas.
 *       Salvos em: {@code sftp.local.directory/radar/}</li>
 * </ul>
 *
 * <p>Subpastas locais separadas evitam colisão de nomes entre as duas fontes.
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

    private static final Pattern PATTERN_DATA_ISO      = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern PATTERN_DATA_YYYYMMDD = Pattern.compile("(?<!\\d)(\\d{8})(?!\\d)");

    // ─────────────────────────────────────────────────────────────
    // API PÚBLICA
    // ─────────────────────────────────────────────────────────────

    /**
     * Baixa arquivos novos de ambas as pastas SFTP.
     * Cada fonte usa um subdiretório local dedicado para evitar conflitos de nome.
     */
    public DownloadResult baixarArquivosNovos(ChannelSftp sftp) throws IOException {
        // Subdiretórios locais separados por fonte
        Path localRecebidos = Path.of(localBaseDirectory, "recebidos");
        Path localRadar     = Path.of(localBaseDirectory, "radar");
        Files.createDirectories(localRecebidos);
        Files.createDirectories(localRadar);

        LocalDate dataLimite = LocalDate.now().minusDays(1);

        // /recebidos — SEM filtro de data, NÃO recursivo
        List<Path> recebidos = baixarPasta(
                sftp, remoteDirRecebidos, localRecebidos,
                listarArquivosLocais(localRecebidos),
                dataLimite, false, false);

        // /radar — COM filtro de data, recursivo em sub-pastas
        List<Path> radar = baixarPasta(
                sftp, remoteDirRadar, localRadar,
                listarArquivosLocais(localRadar),
                dataLimite, true, true);

        log.info("[SFTP] Resultado: {} de /recebidos | {} de /radar.",
                recebidos.size(), radar.size());

        return new DownloadResult(recebidos, radar);
    }

    // ─────────────────────────────────────────────────────────────
    // VARREDURA INTERNA
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Path> baixarPasta(
            ChannelSftp sftp,
            String remotePath,
            Path localPath,
            Set<String> locais,
            LocalDate dataLimite,
            boolean filtrarPorData,
            boolean recursivo) {

        List<Path> baixados = new ArrayList<>();

        try {
            sftp.cd(remotePath);
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");

            log.info("[SFTP] Varrendo '{}' → {} entrada(s) | filtroData={} | recursivo={}",
                    remotePath, entries.size(), filtrarPorData, recursivo);

            for (ChannelSftp.LsEntry entry : entries) {
                String nome = entry.getFilename();
                if (nome.startsWith(".")) continue;

                if (entry.getAttrs().isDir()) {
                    if (recursivo) {
                        // Cria subpasta local espelhando a remota
                        Path subLocal = localPath.resolve(nome);
                        Files.createDirectories(subLocal);
                        baixados.addAll(
                                baixarPasta(sftp, remotePath + "/" + nome,
                                        subLocal, listarArquivosLocais(subLocal),
                                        dataLimite, filtrarPorData, true)
                        );
                        sftp.cd(remotePath);
                    }
                    continue;
                }

                if (locais.contains(nome)) {
                    log.debug("[SFTP] Já existe localmente, ignorando: {}", nome);
                    continue;
                }

                if (filtrarPorData && !isDentroDoPeriodo(nome, dataLimite)) {
                    log.debug("[SFTP] Fora do período, ignorando: {}", nome);
                    continue;
                }

                baixarArquivo(sftp, nome, localPath).ifPresent(baixados::add);
            }

        } catch (SftpException e) {
            log.error("[SFTP] Erro ao varrer '{}': {}", remotePath, e.getMessage());
        } catch (IOException e) {
            log.error("[SFTP] Erro de I/O em '{}': {}", remotePath, e.getMessage());
        }

        return baixados;
    }

    private Optional<Path> baixarArquivo(ChannelSftp sftp, String nome, Path destino) {
        Path alvo = destino.resolve(nome);
        try (InputStream is = sftp.get(nome)) {
            Files.copy(is, alvo, StandardCopyOption.REPLACE_EXISTING);
            log.info("[SFTP] ✅ Baixado: {} → {}", nome, alvo);
            return Optional.of(alvo);
        } catch (Exception e) {
            log.error("[SFTP] ❌ Falha ao baixar '{}': {}", nome, e.getMessage());
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AUXILIARES
    // ─────────────────────────────────────────────────────────────

    private Set<String> listarArquivosLocais(Path dir) {
        if (!Files.exists(dir)) return Collections.emptySet();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            log.warn("[SFTP] Não foi possível listar '{}': {}", dir, e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Para a pasta /radar: verifica se o nome contém data >= dataLimite.
     * Se não contiver data reconhecível, baixa por segurança (retorna true).
     */
    private boolean isDentroDoPeriodo(String nomeArquivo, LocalDate dataLimite) {
        return extrairDataDoNome(nomeArquivo)
                .map(d -> !d.isBefore(dataLimite))
                .orElse(true); // sem data no nome → baixa por segurança
    }

    private Optional<LocalDate> extrairDataDoNome(String nome) {
        Matcher m1 = PATTERN_DATA_ISO.matcher(nome);
        if (m1.find()) {
            try { return Optional.of(LocalDate.parse(m1.group(1))); } catch (Exception ignored) {}
        }
        Matcher m2 = PATTERN_DATA_YYYYMMDD.matcher(nome);
        if (m2.find()) {
            try {
                return Optional.of(LocalDate.parse(
                        m2.group(1), DateTimeFormatter.ofPattern("yyyyMMdd")));
            } catch (Exception ignored) {}
        }
        return Optional.empty();
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