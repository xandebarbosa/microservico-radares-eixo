package com.coruja.services.sftp;

import com.coruja.entities.ArquivoSftpProcessado;
import com.coruja.entities.LocalizacaoRadar;
import com.coruja.entities.Radars;
import com.coruja.enuns.StatusProcessamento;
import com.coruja.enuns.TipoFonte;
import com.coruja.repositories.ArquivoSftpProcessadoRepository;
import com.coruja.repositories.LocalizacaoRadarRepository;
import com.coruja.services.domain.GestaoRodoviaService;
import com.coruja.services.radar.RadarsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lê arquivos locais, faz parse e persiste.
 *
 * <h3>Mudanças em relação à versão anterior:</h3>
 * <ul>
 *   <li>{@code processar()} agora retorna um {@link ResultadoLote} com contagem de registros
 *       salvos e erros por arquivo — usado pelo orquestrador para atualizar o status no banco.</li>
 *   <li>Cache de localização é reutilizado entre arquivos do mesmo lote (construído UMA vez).</li>
 *   <li>Erros em um arquivo não interrompem o processamento dos demais.</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FileProcessor {

    private final RadarLineParser                 parser;
    private final RadarsService                   radarsService;
    private final GestaoRodoviaService            gestaoRodoviaService;
    private final LocalizacaoRadarRepository      localizacaoRepo;
    private final ArquivoSftpProcessadoRepository arquivoRepo;

    // ─────────────────────────────────────────────────────────────
    // API PÚBLICA
    // ─────────────────────────────────────────────────────────────

    /**
     * Processa uma lista de arquivos com o mesmo {@link TipoFonte}.
     *
     * @return {@link ResultadoLote} com o número de registros salvos e arquivos com erro.
     */
    public ResultadoLote processar(List<Path> arquivos, TipoFonte tipoFonte) {
        if (arquivos == null || arquivos.isEmpty()) return ResultadoLote.vazio();

        log.info("[FileProcessor] Processando lote de {} arquivo(s) — fonte: {}",
                arquivos.size(), tipoFonte);

        // Caches construídos UMA vez para todo o lote
        Map<String, LocalizacaoRadar> localCache = carregarLocalCache();
        Map<String, LocalizacaoRadar> pracaCache = carregarPracaCache();

        int totalRegistros = 0;
        int arquivosComErro = 0;

        for (Path arquivo : arquivos) {
            ResultadoArquivo resultado = processarArquivo(arquivo, tipoFonte, localCache, pracaCache);
            totalRegistros += resultado.registrosSalvos();

            // Atualiza status no banco para PROCESSADO ou ERRO
            atualizarStatusNoBanco(arquivo.getFileName().toString(), resultado);

            if (!resultado.sucesso()) {
                arquivosComErro++;
            }
        }

        log.info("[FileProcessor] Lote concluído — {} registro(s), {} arquivo(s) com erro.",
                totalRegistros, arquivosComErro);

        return new ResultadoLote(totalRegistros, arquivos.size() - arquivosComErro, arquivosComErro);
    }

    // ─────────────────────────────────────────────────────────────
    // PROCESSAMENTO POR ARQUIVO
    // ─────────────────────────────────────────────────────────────

    private ResultadoArquivo processarArquivo(
            Path arquivo,
            TipoFonte tipoFonte,
            Map<String, LocalizacaoRadar> localCache,
            Map<String, LocalizacaoRadar> pracaCache) {

        log.info("[FileProcessor] Lendo: {} ({})", arquivo.getFileName(), tipoFonte);

        Map<String, Set<String>> dominios = new HashMap<>();
        List<Radars> lote = new ArrayList<>();

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            linhas.forEach(linha ->
                    parser.parseLine(linha, tipoFonte, localCache, pracaCache)
                            .ifPresent(radar -> {
                                lote.add(radar);
                                coletarDominio(dominios, radar, tipoFonte);
                            })
            );
        } catch (IOException e) {
            log.error("[FileProcessor] Erro ao ler {}: {}", arquivo, e.getMessage());
            return ResultadoArquivo.erro(e.getMessage());
        }

        if (!dominios.isEmpty()) {
            gestaoRodoviaService.registrarDescobertas(dominios);
        }

        if (lote.isEmpty()) {
            log.warn("[FileProcessor] Nenhum registro parseado em: {}", arquivo.getFileName());
            return ResultadoArquivo.sucesso(0);
        }

        try {
            long inicio = System.currentTimeMillis();
            radarsService.saveRadars(lote);
            log.info("[FileProcessor] {} registros salvos em {}ms (arquivo: {}).",
                    lote.size(), System.currentTimeMillis() - inicio, arquivo.getFileName());
            return ResultadoArquivo.sucesso(lote.size());
        } catch (Exception e) {
            log.error("[FileProcessor] Falha ao salvar registros de {}: {}", arquivo.getFileName(), e.getMessage());
            return ResultadoArquivo.erro("Falha ao persistir: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ATUALIZAÇÃO DE STATUS
    // ─────────────────────────────────────────────────────────────

    /**
     * Atualiza o status do arquivo no banco após processamento.
     *
     * <p>Visibilidade package-private (não {@code private}) é intencional:
     * o proxy CGLIB do Spring só intercepta {@code @Transactional} em métodos
     * não-privados. Método privado seria chamado diretamente, ignorando a transação.
     */
    @Transactional
    void atualizarStatusNoBanco(String nomeArquivo, ResultadoArquivo resultado) {
        arquivoRepo.findByNomeArquivo(nomeArquivo).ifPresentOrElse(
                registro -> {
                    if (resultado.sucesso()) {
                        arquivoRepo.atualizarStatus(
                                registro.getId(),
                                StatusProcessamento.PROCESSADO,
                                LocalDateTime.now(),
                                resultado.registrosSalvos()
                        );
                    } else {
                        registro.setStatus(StatusProcessamento.ERRO);
                        registro.setMensagemErro(resultado.mensagemErro());
                        registro.setTentativas(registro.getTentativas() + 1);
                        registro.setProcessadoEm(LocalDateTime.now());
                        arquivoRepo.save(registro);
                    }
                },
                () -> log.warn("[FileProcessor] Registro não encontrado no banco para: {}", nomeArquivo)
        );
    }

    // ─────────────────────────────────────────────────────────────
    // RETRY DE ARQUIVOS COM ERRO
    // ─────────────────────────────────────────────────────────────

    /**
     * Retenta arquivos com status {@code ERRO} já presentes no disco.
     * Chamado pelo orquestrador em ciclos separados.
     */
    public ResultadoLote retentarComErro(List<ArquivoSftpProcessado> elegíveis) {
        if (elegíveis == null || elegíveis.isEmpty()) return ResultadoLote.vazio();

        log.info("[FileProcessor] Retentando {} arquivo(s) com erro.", elegíveis.size());

        Map<String, LocalizacaoRadar> localCache = carregarLocalCache();
        Map<String, LocalizacaoRadar> pracaCache = carregarPracaCache();

        int totalRegistros = 0;
        int arquivosComErro = 0;

        for (ArquivoSftpProcessado registro : elegíveis) {
            Path pasta = registro.getTipoFonte() == TipoFonte.RECEBIDOS
                    ? Path.of(System.getProperty("sftp.local.directory", "."), "recebidos")
                    : Path.of(System.getProperty("sftp.local.directory", "."), "radar");

            Path arquivo = pasta.resolve(registro.getNomeArquivo());

            if (!arquivo.toFile().exists()) {
                log.warn("[FileProcessor] Arquivo para retry não encontrado no disco: {}", arquivo);
                arquivosComErro++;
                continue;
            }

            ResultadoArquivo resultado = processarArquivo(
                    arquivo, registro.getTipoFonte(), localCache, pracaCache);
            totalRegistros += resultado.registrosSalvos();
            atualizarStatusNoBanco(registro.getNomeArquivo(), resultado);

            if (!resultado.sucesso()) arquivosComErro++;
        }

        return new ResultadoLote(totalRegistros, elegíveis.size() - arquivosComErro, arquivosComErro);
    }

    // ─────────────────────────────────────────────────────────────
    // COLETA DE DOMÍNIOS
    // ─────────────────────────────────────────────────────────────

    private void coletarDominio(Map<String, Set<String>> dominios, Radars radar, TipoFonte tipoFonte) {
        String rodovia = radar.getRodovia();
        if (rodovia == null || rodovia.isBlank()) return;

        Set<String> kms = dominios.computeIfAbsent(rodovia, k -> new HashSet<>());

        if (tipoFonte == TipoFonte.RADAR && radar.getKm() != null && !radar.getKm().isBlank()) {
            kms.add(radar.getKm());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CACHES DE LOCALIZAÇÃO
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

    // ─────────────────────────────────────────────────────────────
    // TIPOS AUXILIARES
    // ─────────────────────────────────────────────────────────────

    public record ResultadoArquivo(boolean sucesso, int registrosSalvos, String mensagemErro) {
        static ResultadoArquivo sucesso(int registros) {
            return new ResultadoArquivo(true, registros, null);
        }
        static ResultadoArquivo erro(String msg) {
            return new ResultadoArquivo(false, 0, msg);
        }
    }

    public record ResultadoLote(int registrosTotais, int arquivosSucesso, int arquivosComErro) {
        static ResultadoLote vazio() {
            return new ResultadoLote(0, 0, 0);
        }
    }
}