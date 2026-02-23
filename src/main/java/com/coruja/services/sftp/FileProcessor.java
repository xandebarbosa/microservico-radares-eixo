package com.coruja.services.sftp;

import com.coruja.entities.LocalizacaoRadar;
import com.coruja.entities.Radars;
import com.coruja.enuns.TipoFonte;
import com.coruja.repositories.LocalizacaoRadarRepository;
import com.coruja.services.domain.GestaoRodoviaService;
import com.coruja.services.radar.RadarsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lê arquivos locais, faz o parse de cada linha e persiste os dados.
 *
 * <p>A separação de {@link TipoFonte} garante que:
 * <ul>
 *   <li>Registros de {@code /recebidos} nunca carregam KM.</li>
 *   <li>Registros de {@code /radar} sempre carregam KM (quando disponível).</li>
 *   <li>Consultas futuras conseguem distinguir as fontes via coluna {@code tipo_fonte}.</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FileProcessor {

    private final RadarLineParser         parser;
    private final RadarsService           radarsService;
    private final GestaoRodoviaService    gestaoRodoviaService;
    private final LocalizacaoRadarRepository localizacaoRepo;

    // ─────────────────────────────────────────────────────────────
    // API PÚBLICA
    // ─────────────────────────────────────────────────────────────

    /**
     * Processa uma lista de arquivos, todos com o mesmo {@link TipoFonte}.
     * Os caches de localização são construídos uma única vez por chamada.
     */
    public void processar(List<Path> arquivos, TipoFonte tipoFonte) {
        if (arquivos == null || arquivos.isEmpty()) return;

        log.info("[FileProcessor] Processando {} arquivo(s) — fonte: {}", arquivos.size(), tipoFonte);

        // Caches construídos UMA vez para todos os arquivos do lote
        Map<String, LocalizacaoRadar> localCache  = carregarLocalCache();
        Map<String, LocalizacaoRadar> pracaCache  = carregarPracaCache();

        for (Path arquivo : arquivos) {
            processarArquivo(arquivo, tipoFonte, localCache, pracaCache);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PROCESSAMENTO POR ARQUIVO
    // ─────────────────────────────────────────────────────────────

    private void processarArquivo(
            Path arquivo,
            TipoFonte tipoFonte,
            Map<String, LocalizacaoRadar> localCache,
            Map<String, LocalizacaoRadar> pracaCache) {

        log.info("[FileProcessor] Lendo: {} ({})", arquivo.getFileName(), tipoFonte);

        Map<String, Set<String>> dominios  = new HashMap<>();
        List<Radars>             lote      = new ArrayList<>();

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            linhas.forEach(linha ->
                    parser.parseLine(linha, tipoFonte, localCache, pracaCache)
                            .ifPresent(radar -> {
                                lote.add(radar);
                                coletarDominio(dominios, radar, tipoFonte);
                            })
            );
        } catch (IOException e) {
            log.error("[FileProcessor] Erro ao ler {}: {}", arquivo, e);
            return;
        }

        if (!dominios.isEmpty()) {
            gestaoRodoviaService.registrarDescobertas(dominios);
        }

        if (!lote.isEmpty()) {
            long inicio = System.currentTimeMillis();
            radarsService.saveRadars(lote);
            log.info("[FileProcessor] {} registros salvos em {}ms.",
                    lote.size(), System.currentTimeMillis() - inicio);
        }
    }

    /**
     * Acumula rodovias e KMs descobertos no arquivo.
     * Para RECEBIDOS, o conjunto de KMs permanece vazio (nunca há KM neste fluxo).
     */
    private void coletarDominio(Map<String, Set<String>> dominios, Radars radar, TipoFonte tipoFonte) {
        String rodovia = radar.getRodovia();
        if (rodovia == null || rodovia.isBlank()) return;

        Set<String> kms = dominios.computeIfAbsent(rodovia, k -> new HashSet<>());

        if (tipoFonte == TipoFonte.RADAR && radar.getKm() != null && !radar.getKm().isBlank()) {
            kms.add(radar.getKm());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CACHES
    // ─────────────────────────────────────────────────────────────

    private Map<String, LocalizacaoRadar> carregarLocalCache() {
        return localizacaoRepo.findAll().stream()
                .collect(Collectors.toMap(
                        l -> parser.chaveCache(l.getRodovia(), l.getKm()),
                        l -> l,
                        (a, b) -> a
                ));
    }

    private Map<String, LocalizacaoRadar> carregarPracaCache() {
        return localizacaoRepo.findAll().stream()
                .filter(l -> l.getPraca() != null)
                .collect(Collectors.toMap(
                        l -> parser.normalizar(l.getPraca()),
                        l -> l,
                        (a, b) -> a
                ));
    }
}
