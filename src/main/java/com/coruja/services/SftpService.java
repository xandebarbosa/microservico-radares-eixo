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
    @Value("${sftp.local.directory}")
    private String localDirectory;
    @Value("${sftp.timeout:30000}") // 30 segundos de timeout
    private int sftpTimeout;

    // Cache temporário para evitar milhares de consultas ao banco por arquivo
    private Map<String, LocalizacaoRadar> localizacaoCache = new HashMap<>();

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
        log.info("Iniciando verificação de arquivos no SFTP (Eixo) às {}...", lastExecutionTime);

        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            // 1. CARREGAR CACHE: Busca todas as localizações antes de processar as linhas
            // Isso evita o erro de "Connection Reset" por excesso de queries pequenas.
            carregarCacheLocalizacoes();

            // 2. Tentar conexão com lógica de retry manual simples ou tratamento de erro
            session = conectarSessaoSftp();
            if (session == null || !session.isConnected()) {
                log.error("❌ Falha crítica: Não foi possível estabelecer conexão com o servidor SFTP.");
                return;
            }

            sftpChannel = abrirCanalSftp(session);
            sftpChannel.cd(remoteDirectory);

            Path localPath = Path.of(localDirectory);
            Files.createDirectories(localPath);

            Set<String> arquivosLocais = listarArquivosLocais(localPath);
            Vector<ChannelSftp.LsEntry> arquivosRemotos = sftpChannel.ls(".");

            if (arquivosRemotos.isEmpty()) {
                log.info("Nenhum arquivo encontrado no diretório do SFTP.");
                return;
            }

            LocalDate dataLimite = LocalDate.now().minusDays(1);
            List<ChannelSftp.LsEntry> novosArquivos = arquivosRemotos.stream()
                    .filter(entry -> !entry.getAttrs().isDir() && !entry.getFilename().startsWith("."))
                    .filter(entry -> !arquivosLocais.contains(entry.getFilename()))
                    .filter(entry -> isDentroDoPeriodo(entry.getFilename(), dataLimite))
                    .collect(Collectors.toList());

            if (novosArquivos.isEmpty()) {
                log.warn("Nenhum arquivo novo dentro do período para processar.");
                return;
            }

            List<Radars> todosOsRadares = new ArrayList<>();
            for (ChannelSftp.LsEntry entry : novosArquivos) {
                baixarArquivo(sftpChannel, entry.getFilename(), localPath).ifPresent(arquivoLocal -> {
                    List<Radars> radaresDoArquivo = processarArquivoLocal(arquivoLocal);
                    todosOsRadares.addAll(radaresDoArquivo);
                });
            }

            if (!todosOsRadares.isEmpty()) {
                radarsService.saveRadars(todosOsRadares);
                log.info("Sucesso: {} registros processados.", todosOsRadares.size());
            }

        } catch (JSchException e) {
            log.error("🌐 Erro de conexão SFTP (Internet/Servidor fora): {}", e.getMessage());
        } catch (SftpException e) {
            log.error("📁 Erro de permissão ou diretório no SFTP: {}", e.getMessage());
        } catch (Exception e) {
            log.error("⚠️ Erro inesperado no processamento SFTP: ", e);
        } finally {
            desconectarSftp(sftpChannel, session);
            localizacaoCache.clear(); // Limpa o cache para liberar memória
            log.info("*** Processamento finalizado ***");
        }
    }

    private void carregarCacheLocalizacoes() {
        log.info("🧠 Carregando localizações para cache em memória...");
        try {
            List<LocalizacaoRadar> lista = localizacaoRadarRepository.findAll();
            this.localizacaoCache = lista.stream()
                    .collect(Collectors.toMap(
                            l -> gerarChaveCache(l.getRodovia(), l.getKm()),
                            l -> l,
                            (existente, novo) -> existente // Em caso de duplicata no banco, mantém o primeiro
                    ));
            log.info("🧠 Cache carregado com {} localizações.", localizacaoCache.size());
        } catch (Exception e) {
            log.error("Falha ao carregar cache de localizações: ", e);
        }
    }

    private String gerarChaveCache(String rodovia, String km) {
        if (rodovia == null) rodovia = "";
        if (km == null) km = "";
        // Normaliza para evitar erros de espaços ou caixa alta/baixa
        return (rodovia.trim() + "|" + km.trim()).toUpperCase();
    }

    private Radars parseLine(String linha) {
        String[] dados = linha.split(";", -1);

        if (dados.length < 4) return null;

        try {
            String dataHoraStr = dados[0].trim();
            String placa = tratarPlaca(dados[1].trim());
            String rodovia = dados[2].trim().replaceAll("\\s+", " ");
            String sentido = dados[3].trim().replaceAll("\\s+", " ");

            String praca = "";
            String km = ""; // Campo vazio conforme seu log de exemplo

            String[] dataHoraSplit = dataHoraStr.split("T");
            LocalDate data = LocalDate.parse(dataHoraSplit[0]);
            LocalTime hora = LocalTime.parse(dataHoraSplit[1]);

            // BUSCA NO CACHE (Sem bater no banco de dados)
            LocalizacaoRadar localizacaoDoRadar = localizacaoCache.get(gerarChaveCache(praca, km));

            if (localizacaoDoRadar == null) {
                log.debug("Localização não encontrada no cache para: {}", praca);
            }


            return new Radars(data, hora, placa, rodovia, praca, km, sentido, localizacaoDoRadar);
        } catch (Exception e) {
            log.error("Erro no parsing da linha: {}. Causa: {}", linha, e.getMessage());
            return null;
        }
    }

    // --- MANTIDOS MÉTODOS AUXILIARES (tratarPlaca, baixarArquivo, etc) ---
    private String tratarPlaca(String placa) {
        if (placa == null || placa.isBlank()) return "N/I";
        placa = placa.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
        return placa.length() > 7 ? placa.substring(0, 7) : placa;
    }

    private boolean isDentroDoPeriodo(String nomeArquivo, LocalDate dataLimite) {
        return extrairDataDoNome(nomeArquivo)
                .map(dataArquivo -> !dataArquivo.isBefore(dataLimite))
                .orElse(false);
    }

    private Optional<LocalDate> extrairDataDoNome(String nomeArquivo) {
        try {
            Pattern pattern = Pattern.compile("(\\d{8})");
            Matcher matcher = pattern.matcher(nomeArquivo);
            if (matcher.find()) {
                return Optional.of(LocalDate.parse(matcher.group(1), DateTimeFormatter.ofPattern("yyyyMMdd")));
            }
        } catch (DateTimeParseException e) {
            log.warn("Data inválida no arquivo: {}", nomeArquivo);
        }
        return Optional.empty();
    }

    private List<Radars> processarArquivoLocal(Path arquivoLocal) {

        log.info("📂 Abrindo arquivo para processamento: {}", arquivoLocal.getFileName());
        // Mapa para coletar descobertas de domínio (Praca -> Lista de KMs)
        Map<String, Set<String>> descobertas = new HashMap<>();

        try (Stream<String> lines = Files.lines(arquivoLocal, StandardCharsets.UTF_8)) {
            // Log das primeiras 3 linhas brutas do arquivo para conferência de formato
            List<String> amostraBruta = Files.lines(arquivoLocal, StandardCharsets.UTF_8)
                    .limit(3)
                    .collect(Collectors.toList());
            log.info("📝 Amostra do conteúdo bruto (Primeiras 3 linhas):");
            amostraBruta.forEach(l -> log.info("   > {}", l));

            List<Radars> resultado = lines.map(linha -> {
                        Radars r = parseLine(linha);
                        if (r != null) {
                            // Adiciona ao mapa de descobertas para popular as tabelas de domínio
                            descobertas.computeIfAbsent(r.getRodovia(), k -> new HashSet<>()).add(r.getKm());
                        }
                        return r;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // CHAMA O APRENDIZADO DE DOMÍNIO (Popula as tabelas pracas e kms_praca)
            if (!descobertas.isEmpty()) {
                gestaoRodoviaService.registrarDescobertas(descobertas);
            }

            if (!resultado.isEmpty()) {
                radarsService.saveRadars(resultado);
            }

            // Log de conferência dos objetos mapeados (Amostra de 2 registros)
            if (!resultado.isEmpty()) {
                log.info("✅ Mapeamento bem sucedido. Exemplo de dados processados:");
                resultado.stream().limit(2).forEach(r ->
                        log.info("   [OBJETO] Data: {}, Placa: {}, Localidade: {}",
                                r.getData(), r.getPlaca(), r.getRodovia())
                );
            }

            return resultado;
        } catch (IOException e) {
            log.error("❌ Erro ao ler arquivo: {}", arquivoLocal, e);
            return Collections.emptyList();
        }
    }

    private Session conectarSessaoSftp() throws Exception {
        try {
            JSch jsch = new JSch();
            Session session = jsch.getSession(sftpUser, sftpHost, sftpPort);
            session.setPassword(sftpPass);
            session.setConfig("StrictHostKeyChecking", "no");

            // ADICIONADO: Timeout para não deixar a thread travada eternamente se a internet cair
            session.connect(sftpTimeout);

            log.info("✅ Sessão SFTP conectada.");
            return session;
        } catch (Exception e) {
            log.error("❌ Erro ao conectar no host {}: {}", sftpHost, e.getMessage());
            throw e;
        }
    }

    private ChannelSftp abrirCanalSftp(Session session) throws Exception {
        ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
        sftpChannel.connect();
        return sftpChannel;
    }

    private void desconectarSftp(ChannelSftp channel, Session session) {
        if (channel != null && channel.isConnected()) channel.disconnect();
        if (session != null && session.isConnected()) session.disconnect();
    }

    private Optional<Path> baixarArquivo(ChannelSftp sftpChannel, String nomeArquivo, Path diretorioLocal) {
        Path arquivoLocal = diretorioLocal.resolve(nomeArquivo);
        try (InputStream inputStream = sftpChannel.get(nomeArquivo)) {
            Files.copy(inputStream, arquivoLocal, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(arquivoLocal);
        } catch (Exception e) {
            log.error("Erro no download: {}", nomeArquivo);
            return Optional.empty();
        }
    }

    private Set<String> listarArquivosLocais(Path diretorioLocal) {
        if (!Files.exists(diretorioLocal)) return Collections.emptySet();
        try (Stream<Path> stream = Files.list(diretorioLocal)) {
            return stream.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        } catch (IOException e) {
            return Collections.emptySet();
        }
    }
}