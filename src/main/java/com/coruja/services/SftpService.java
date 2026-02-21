package com.coruja.services;

import com.coruja.entities.LocalizacaoRadar;
import com.coruja.entities.Radars;
import com.coruja.repositories.LocalizacaoRadarRepository;
import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class SftpService {

    @Value("${sftp.host}")
    private String sftpHost;
    @Value("${sftp.port}")
    private int sftpPort;
    @Value("${sftp.user}")
    private String sftpUser;
    @Value("${sftp.pass}")
    private String sftpPass;
    @Value("${sftp.remote.directory}")
    private String remoteDirectory;
    @Value("${sftp.remote.radar.directory:/radar}")
    private String remoteRadarDirectory;
    @Value("${sftp.local.directory}")
    private String localDirectory;
    @Value("${sftp.timeout:30000}")
    private int sftpTimeout;

    // Cache local para performance
    private Map<String, LocalizacaoRadar> localizacaoCache = new HashMap<>();
    private Map<String, LocalizacaoRadar> pracaCache = new HashMap<>();

    private final RadarsService radarsService;
    private final LocalizacaoRadarRepository localizacaoRadarRepository;
    private final GestaoRodoviaService gestaoRodoviaService;

    @Autowired
    public SftpService(
            RadarsService radarsService,
            LocalizacaoRadarRepository localizacaoRadarRepository,
            GestaoRodoviaService gestaoRodoviaService
    ) {
        this.radarsService = radarsService;
        this.localizacaoRadarRepository = localizacaoRadarRepository;
        this.gestaoRodoviaService = gestaoRodoviaService;
    }

    @Scheduled(fixedRateString = "${sftp.schedule.rate.ms}")
    public void processarSftpEixo() {
        LocalDateTime lastExecutionTime = LocalDateTime.now();
        log.info("Iniciando verificação SFTP (Eixo) às {}...", lastExecutionTime);

        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            // 1. Pré-carrega caches para evitar N+1 selects
            carregarCaches();

            session = conectarSessaoSftp();
            if (session == null || !session.isConnected()) return;

            sftpChannel = abrirCanalSftp(session);

            Path localPath = Path.of(localDirectory);
            Files.createDirectories(localPath);
            Set<String> arquivosLocais = listarArquivosLocais(localPath);

            // Processa apenas arquivos de hoje e ontem
            LocalDate dataLimite = LocalDate.now().minusDays(1);

            // --- FLUXO 1: Pasta Praças ---
            log.info("📂 Varrendo Praças: {}", remoteDirectory);
            processarPasta(sftpChannel, remoteDirectory, localPath, arquivosLocais, dataLimite);

            // --- FLUXO 2: Pasta Radares ---
            log.info("📂 Varrendo Radares: {}", remoteRadarDirectory);
            processarPastaRecursiva(sftpChannel, remoteRadarDirectory, localPath, arquivosLocais, dataLimite);

        } catch (Exception e) {
            log.error("⚠️ Erro no ciclo SFTP: ", e);
        } finally {
            desconectarSftp(sftpChannel, session);
            localizacaoCache.clear();
            pracaCache.clear();
            log.info("*** Ciclo finalizado ***");
        }
    }

    // --- LÓGICA CORE: BAIXAR -> PROCESSAR -> SALVAR (SEM ACUMULAR) ---

    private void processarPasta(ChannelSftp sftp, String path, Path localPath, Set<String> locais, LocalDate limite) throws SftpException {
        sftp.cd(path);
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");
        for (ChannelSftp.LsEntry entry : entries) {
            String nome = entry.getFilename();
            if (!entry.getAttrs().isDir() && !nome.startsWith(".") && !locais.contains(nome)) {
                if (isDentroDoPeriodo(nome, limite)) {
                    baixarEProcessar(sftp, nome, localPath);
                }
            }
        }
    }

    private void processarPastaRecursiva(ChannelSftp sftp, String path, Path localPath, Set<String> locais, LocalDate limite) {
        try {
            sftp.cd(path);
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");
            for (ChannelSftp.LsEntry entry : entries) {
                String nome = entry.getFilename();
                if (nome.equals(".") || nome.equals("..") || nome.startsWith(".")) continue;

                if (entry.getAttrs().isDir()) {
                    processarPastaRecursiva(sftp, path + "/" + nome, localPath, locais, limite);
                    sftp.cd(path);
                } else if (!locais.contains(nome) && isDentroDoPeriodo(nome, limite)) {
                    baixarEProcessar(sftp, nome, localPath);
                }
            }
        } catch (SftpException e) {
            log.error("Erro ao acessar caminho {}: {}", path, e.getMessage());
        }
    }

    private void baixarEProcessar(ChannelSftp sftp, String nome, Path localPath) {
        baixarArquivo(sftp, nome, localPath).ifPresent(this::processarArquivoLocal);
    }

    private void processarArquivoLocal(Path arquivoLocal) {
        log.info("📄 Processando arquivo: {}", arquivoLocal.getFileName());

        Map<String, Set<String>> dominiosDescobertos = new HashMap<>();
        List<Radars> loteParaSalvar = new ArrayList<>();

        try (Stream<String> lines = Files.lines(arquivoLocal, StandardCharsets.UTF_8)) {
            lines.forEach(linha -> {
                Radars r = parseLine(linha);
                if (r != null) {
                    loteParaSalvar.add(r);

                    // Lógica para separar Rodovia e KM
                    if (r.getRodovia() != null && !r.getRodovia().isBlank()) {
                        dominiosDescobertos.computeIfAbsent(r.getRodovia(), k -> new HashSet<>());

                        // Só adiciona o KM se ele existir no arquivo (ignora os vazios da pasta /recebidos)
                        if (r.getKm() != null && !r.getKm().isBlank()) {
                            dominiosDescobertos.get(r.getRodovia()).add(r.getKm());
                        }
                    }
                }
            });

            // 1. Atualiza Domínio (Rodovias e KMs)
            if (!dominiosDescobertos.isEmpty()) {
                gestaoRodoviaService.registrarDescobertas(dominiosDescobertos);
            }

            // 2. Salva Lote Único do Arquivo
            if (!loteParaSalvar.isEmpty()) {
                long inicio = System.currentTimeMillis();
                radarsService.saveRadars(loteParaSalvar);
                long fim = System.currentTimeMillis();
                log.info("💾 Salvos {} registros em {}ms.", loteParaSalvar.size(), (fim - inicio));
            }

        } catch (IOException e) {
            log.error("❌ Erro ao ler arquivo: {}", arquivoLocal, e);
        }
    }

    // --- CACHE E PARSE (Lógica mantida e otimizada) ---

    private void carregarCaches() {
        log.info("🧠 Carregando caches...");
        try {
            List<LocalizacaoRadar> lista = localizacaoRadarRepository.findAll();
            // Cache de Trecho
            this.localizacaoCache = lista.stream()
                    .collect(Collectors.toMap(
                            l -> gerarChaveCache(l.getRodovia(), l.getKm()),
                            l -> l,
                            (a, b) -> a
                    ));
            // Cache de Praça
            this.pracaCache = lista.stream()
                    .filter(l -> l.getPraca() != null)
                    .collect(Collectors.toMap(
                            l -> normalizarTexto(l.getPraca()),
                            l -> l,
                            (a, b) -> a
                    ));
            log.info("🧠 Caches prontos: {} trechos, {} praças.", localizacaoCache.size(), pracaCache.size());
        } catch (Exception e) { log.error("Erro no cache: ", e); }
    }

    private String gerarChaveCache(String rodovia, String km) {
        return (normalizarTexto(rodovia) + "|" + normalizarTexto(km));
    }

    private String normalizarTexto(String texto) {
        if (texto == null) return "";
        String nfdNormalizedString = Normalizer.normalize(texto, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        return pattern.matcher(nfdNormalizedString).replaceAll("").trim().toUpperCase();
    }

    private Radars parseLine(String linha) {
        String[] dados = linha.split(";", -1);
        if (dados.length < 4) return null;
        try {
            String dataHoraStr = dados[0].trim();
            String placa = tratarPlaca(dados[1].trim());
            if (placa.length() < 7 && !placa.equals("N/I")) {
                return null; // Ignora se inválida
            }
            String localizacaoBruta = dados[2].trim();
            String sentido = dados[3].trim().replaceAll("\\s+", " ");
            String[] dataHoraSplit = dataHoraStr.split("T");
            LocalDate data = LocalDate.parse(dataHoraSplit[0]);
            String horaNormalizada = dataHoraSplit[1].replace("-", ":").split("\\.")[0];
            LocalTime hora = LocalTime.parse(horaNormalizada);

            String rodoviaFinal = "";
            String kmFinal = "";
            LocalizacaoRadar localizacaoVinculada = null;
            String nomeRodoviaUpper = localizacaoBruta.toUpperCase();

            if (nomeRodoviaUpper.contains("KM:") || nomeRodoviaUpper.contains("METROS:")) {
                rodoviaFinal = extrairRodoviaDoTexto(localizacaoBruta);
                kmFinal = extrairKmFormatado(localizacaoBruta);
                localizacaoVinculada = localizacaoCache.get(gerarChaveCache(rodoviaFinal, kmFinal));
            } else {
                String chaveBusca = normalizarTexto(localizacaoBruta);
                localizacaoVinculada = pracaCache.get(chaveBusca);
                if (localizacaoVinculada != null) {
                    rodoviaFinal = localizacaoVinculada.getRodovia();
                    kmFinal = localizacaoVinculada.getKm();
                } else {
                    rodoviaFinal = localizacaoBruta;
                    kmFinal = "";
                }
            }
            if (rodoviaFinal != null && rodoviaFinal.length() > 100) rodoviaFinal = rodoviaFinal.substring(0, 100);
            return new Radars(data, hora, placa, rodoviaFinal, kmFinal, sentido, localizacaoVinculada);
        } catch (Exception e) { return null; }
    }

    // --- MÉTODOS AUXILIARES (Regex e Tratamento) ---
    private String extrairRodoviaDoTexto(String t) { Pattern p = Pattern.compile("([A-Z]{2,3}-?\\d{3}(?:/\\d{3})?)", Pattern.CASE_INSENSITIVE); Matcher m = p.matcher(t); return m.find() ? m.group(1).toUpperCase() : ""; }
    private String extrairKmFormatado(String t) {
        Pattern pk = Pattern.compile("km[:\\s]*(\\d+)", 2); Pattern pm = Pattern.compile("metros[:\\s]*(\\d+)", 2);
        Matcher mk = pk.matcher(t); Matcher mm = pm.matcher(t);
        String vk = mk.find() ? String.format("%03d", Integer.parseInt(mk.group(1))) : "000";
        String vm = mm.find() ? String.format("%03d", Integer.parseInt(mm.group(1))) : "000";
        return (vk.equals("000") && vm.equals("000")) ? "" : vk + "+" + vm;
    }

    private String tratarPlaca(String placa) {
        if (placa == null || placa.isBlank()) return "N/I";

        // Remove tudo que não for letra ou número e converte para maiúsculo
        String limpa = placa.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

        // CORREÇÃO CRÍTICA: Garante que nunca exceda 7 caracteres
        if (limpa.length() > 7) {
            log.warn("⚠️ Placa truncada (original '{}'): '{}'", placa, limpa);
            return limpa.substring(0, 7);
        }

        return limpa;
    }

    private boolean isDentroDoPeriodo(String n, LocalDate d) { return extrairDataDoNome(n).map(da -> !da.isBefore(d)).orElse(false); }
    private Optional<LocalDate> extrairDataDoNome(String n) {
        try {
            Matcher m1 = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(n); if (m1.find()) return Optional.of(LocalDate.parse(m1.group(1)));
            Matcher m2 = Pattern.compile("(\\d{8})").matcher(n); if (m2.find()) return Optional.of(LocalDate.parse(m2.group(1), DateTimeFormatter.ofPattern("yyyyMMdd")));
        } catch (Exception e) {} return Optional.empty();
    }

    // --- CONEXÃO SFTP ---
    private Session conectarSessaoSftp() throws Exception { JSch j = new JSch(); Session s = j.getSession(sftpUser, sftpHost, sftpPort); s.setPassword(sftpPass); s.setConfig("StrictHostKeyChecking", "no"); s.connect(sftpTimeout); return s; }
    private ChannelSftp abrirCanalSftp(Session s) throws Exception { ChannelSftp c = (ChannelSftp) s.openChannel("sftp"); c.connect(); return c; }
    private void desconectarSftp(ChannelSftp c, Session s) { if (c != null && c.isConnected()) c.disconnect(); if (s != null && s.isConnected()) s.disconnect(); }
    private Optional<Path> baixarArquivo(ChannelSftp c, String n, Path d) {
        Path p = d.resolve(n);
        try (InputStream i = c.get(n)) { Files.copy(i, p, StandardCopyOption.REPLACE_EXISTING); return Optional.of(p); }
        catch (Exception e) { log.error("Erro download: {}", n); return Optional.empty(); }
    }
    private Set<String> listarArquivosLocais(Path d) { if (!Files.exists(d)) return Collections.emptySet(); try (Stream<Path> s = Files.list(d)) { return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).collect(Collectors.toSet()); } catch (IOException e) { return Collections.emptySet(); } }
}