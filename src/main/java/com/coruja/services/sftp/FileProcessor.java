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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
@RequiredArgsConstructor
public class FileProcessor {

    private final RadarLineParser                 parser;
    private final RadarsService                   radarsService;
    private final GestaoRodoviaService            gestaoRodoviaService;
    private final LocalizacaoRadarRepository      localizacaoRepo;
    private final ArquivoSftpProcessadoRepository arquivoRepo;

    // 🟢 LÊ CORRETAMENTE A PASTA DO APPLICATION.PROPERTIES (OU DOCKER)
    @Value("${sftp.local.directory:/app/radar}")
    private String localBaseDirectory;

    // Cache em memória global: Evita N operações de findAll() bloqueantes a cada lote
    private final Map<String, LocalizacaoRadar> cacheLocalizacoesGlobais = new ConcurrentHashMap<>();
    
    // Padrões estáticos pré-compilados para máxima performance
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.+?)\\s+(SP\\S+)\\s+(KM\\S+)$"
    );
    
    @PostConstruct
    public void inicializarCacheGlobais() {
        carregarLocalCacheParaMemoria();
    }

    private synchronized void carregarLocalCacheParaMemoria() {
        cacheLocalizacoesGlobais.clear();
        localizacaoRepo.findAll().forEach(l ->
                cacheLocalizacoesGlobais.put(parser.chaveCache(l.getRodovia(), l.getKm()), l)
        );
        log.info("[FileProcessor] Cache de Localizações (Rodovias) inicializado com {} registros.", cacheLocalizacoesGlobais.size());
    }

    public ResultadoLote processar(List<Path> arquivos, TipoFonte tipoFonte) {
        if (arquivos == null || arquivos.isEmpty()) return ResultadoLote.vazio();

        log.info("[FileProcessor] Processando lote de {} arquivo(s) — fonte: {}", arquivos.size(), tipoFonte);

        int totalRegistros = 0;
        int arquivosComErro = 0;
        boolean novosDominiosDescobertos = false;

        for (Path arquivo : arquivos) {
            ResultadoArquivo resultado = processarArquivo(arquivo, tipoFonte);
            totalRegistros += resultado.registrosSalvos();

            if (resultado.novosDominiosRegistrados()) {
                novosDominiosDescobertos = true;
            }

            atualizarStatusNoBanco(arquivo.getFileName().toString(), resultado);

            if (!resultado.sucesso()) {
                arquivosComErro++;
            }
        }

        // Se encontrou dados novos, atualiza o cache para os próximos lotes assíncronos
        if (novosDominiosDescobertos) {
            carregarLocalCacheParaMemoria();
        }

        log.info("[FileProcessor] Lote concluído — {} registro(s), {} arquivo(s) com erro.",
                totalRegistros, arquivosComErro);

        return new ResultadoLote(totalRegistros, arquivos.size() - arquivosComErro, arquivosComErro);
    }

    private ResultadoArquivo processarArquivo(Path arquivo, TipoFonte tipoFonte) {
        log.info("[FileProcessor] Lendo: {} ({})", arquivo.getFileName(), tipoFonte);

        Map<String, Set<String>> dominios = new HashMap<>();
        List<Radars> lote = new ArrayList<>();

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.ISO_8859_1)) {
            linhas.forEach(linha ->
                    parser.parseLine(linha, tipoFonte, cacheLocalizacoesGlobais)
                            .ifPresent(radar -> {
                                lote.add(radar);
                                coletarDominio(dominios, radar, tipoFonte);
                            })
            );
        } catch (IOException e) {
            log.error("[FileProcessor] Erro ao ler {}: {}", arquivo, e.getMessage());
            return ResultadoArquivo.erro(e.getMessage());
        }

        boolean teveDominiosRegistrados = false;
        if (!dominios.isEmpty()) {
            // Só retorna true se o GestaoRodoviaService realmente inseriu algo no banco
            if (gestaoRodoviaService.registrarDescobertas(dominios)) {
                teveDominiosRegistrados = true;
                radarsService.invalidarCacheMapaLocalizacoes(); // <-- Aqui o Front-End é notificado!
            }
        }

        if (lote.isEmpty()) {
            log.warn("[FileProcessor] Nenhum registro parseado em: {}", arquivo.getFileName());
            return ResultadoArquivo.sucesso(0, teveDominiosRegistrados);
        }

        try {
            long inicio = System.currentTimeMillis();
            radarsService.saveRadars(lote);

            log.info("[FileProcessor] {} registros salvos em {}ms | Rodovias: {} (arquivo: {}).",
                    lote.size(), System.currentTimeMillis() - inicio, dominios.keySet(), arquivo.getFileName());

            return ResultadoArquivo.sucesso(lote.size(), teveDominiosRegistrados);
        } catch (Exception e) {
            log.error("[FileProcessor] Falha ao salvar registros de {}: {}", arquivo.getFileName(), e.getMessage());
            return ResultadoArquivo.erro("Falha ao persistir: " + e.getMessage());
        }
    }

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

    public ResultadoLote retentarComErro(List<ArquivoSftpProcessado> elegiveis) {
        if (elegiveis == null || elegiveis.isEmpty()) return ResultadoLote.vazio();

        log.info("[FileProcessor] Retentando {} arquivo(s) com erro.", elegiveis.size());

        int totalRegistros = 0;
        int arquivosComErro = 0;
        boolean novosDominios = false;

        Path baseDir = Path.of(localBaseDirectory);
        Map<String, Path> cacheDisco = new HashMap<>();
        try (Stream<Path> stream = Files.walk(baseDir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> cacheDisco.put(p.getFileName().toString(), p));
        } catch (IOException e) {
            log.error("[FileProcessor] Falha ao mapear diretórios locais.", e);
        }

        for (ArquivoSftpProcessado registro : elegiveis) {
            Path arquivo = cacheDisco.get(registro.getNomeArquivo());

            if (arquivo == null) {
                log.warn("[FileProcessor] Arquivo apagado do disco (Cancelando retry): {}", registro.getNomeArquivo());
                arquivosComErro++;

                // 🟢 CORREÇÃO: Agora notificamos o banco que deu erro por falta do arquivo físico.
                // Isso fará a coluna 'tentativas' incrementar. Quando chegar em 3, ele para de tentar.
                atualizarStatusNoBanco(
                        registro.getNomeArquivo(),
                        ResultadoArquivo.erro("Arquivo físico não encontrado no disco local para retry.")
                );
                continue;
            }

            ResultadoArquivo resultado = processarArquivo(arquivo, registro.getTipoFonte());
            totalRegistros += resultado.registrosSalvos();
            if (resultado.novosDominiosRegistrados()) novosDominios = true;

            atualizarStatusNoBanco(registro.getNomeArquivo(), resultado);

            if (!resultado.sucesso()) arquivosComErro++;
        }

        if (novosDominios) carregarLocalCacheParaMemoria();

        return new ResultadoLote(totalRegistros, elegiveis.size() - arquivosComErro, arquivosComErro);
    }

    private void coletarDominio(Map<String, Set<String>> dominios, Radars radar, TipoFonte tipoFonte) {
        String rodovia = radar.getRodovia();
        if (rodovia == null || rodovia.isBlank()) return;

        Set<String> kms = dominios.computeIfAbsent(rodovia, k -> new HashSet<>());

        if (tipoFonte == TipoFonte.RADAR && radar.getKm() != null && !radar.getKm().isBlank()) {
            kms.add(radar.getKm());
        }
    }

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

    public record ResultadoArquivo(boolean sucesso, int registrosSalvos, String mensagemErro, boolean novosDominiosRegistrados) {
        static ResultadoArquivo sucesso(int registros, boolean novosDominios) {
            return new ResultadoArquivo(true, registros, null, novosDominios);
        }
        static ResultadoArquivo erro(String msg) {
            return new ResultadoArquivo(false, 0, msg, false);
        }
    }

    public record ResultadoLote(int registrosTotais, int arquivosSucesso, int arquivosComErro) {
        static ResultadoLote vazio() {
            return new ResultadoLote(0, 0, 0);
        }
    }
}