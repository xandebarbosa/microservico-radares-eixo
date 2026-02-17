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
    @Value("${sftp.remote.radar.directory:/radar}") // Default para /radar se não houver no properties
    private String remoteRadarDirectory;
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

            // 3. Cria diretório local
            Path localPath = Path.of(localDirectory);
            Files.createDirectories(localPath);
            Set<String> arquivosLocais = listarArquivosLocais(localPath);

            List<Radars> todosOsRadares = new ArrayList<>();
            LocalDate dataLimite = LocalDate.now().minusDays(1);

            // --- FLUXO 1: Pasta Padrão (Recebidos) ---
            log.info("Processando diretório raiz: {}", remoteDirectory);
            processarPasta(sftpChannel, remoteDirectory, localPath, arquivosLocais, dataLimite, todosOsRadares);

            // --- FLUXO 2: Pasta Radar (Recursivo para subpastas) ---
            log.info("Processando diretório de radares: {}", remoteRadarDirectory);
            processarPastaRecursiva(sftpChannel, remoteRadarDirectory, localPath, arquivosLocais, dataLimite, todosOsRadares);

            // Salvamento único para performance
            if (!todosOsRadares.isEmpty()) {
                radarsService.saveRadars(todosOsRadares);
                log.info("Sucesso total: {} registros processados de todas as origens.", todosOsRadares.size());
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

    /**
     * Processa uma pasta específica (sem entrar em subpastas)
     */
    private void processarPasta(ChannelSftp sftp, String path, Path localPath, Set<String> locais, LocalDate limite, List<Radars> listaGeral) throws SftpException {
        sftp.cd(path);
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");

        for (ChannelSftp.LsEntry entry : entries) {
            String nome = entry.getFilename();
            if (!entry.getAttrs().isDir() && !nome.startsWith(".") && !locais.contains(nome)) {
                if (isDentroDoPeriodo(nome, limite)) {
                    baixarEProcessar(sftp, nome, localPath, listaGeral);
                }
            }
        }
    }

    /**
     * Navega recursivamente em qualquer profundidade (ex: /radar/FSCII6521/20250728/)
     */
    private void processarPastaRecursiva(ChannelSftp sftp, String path, Path localPath, Set<String> locais, LocalDate limite, List<Radars> listaGeral) {
        try {
            log.debug("Explorando: {}", path);
            sftp.cd(path);
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(".");

            for (ChannelSftp.LsEntry entry : entries) {
                String nome = entry.getFilename();
                if (nome.equals(".") || nome.equals("..") || nome.startsWith(".")) continue;

                if (entry.getAttrs().isDir()) {
                    // Se for pasta, entra recursivamente usando o caminho completo
                    processarPastaRecursiva(sftp, path + "/" + nome, localPath, locais, limite, listaGeral);
                    sftp.cd(path); // Volta para o nível atual
                } else {
                    // Se for arquivo, valida data e se já existe localmente
                    if (!locais.contains(nome) && isDentroDoPeriodo(nome, limite)) {
                        log.info("✨ Novo arquivo detectado em subpasta: {}", nome);
                        baixarEProcessar(sftp, nome, localPath, listaGeral);
                    }
                }
            }
        } catch (SftpException e) {
            log.error("Erro ao acessar caminho {}: {}", path, e.getMessage());
        }
    }

    private void baixarEProcessar(ChannelSftp sftp, String nome, Path localPath, List<Radars> listaGeral) {
        baixarArquivo(sftp, nome, localPath).ifPresent(arquivo -> {
            List<Radars> processados = processarArquivoLocal(arquivo);
            listaGeral.addAll(processados);
        });
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
            String localizacaoBruta = dados[2].trim();
            String sentido = dados[3].trim().replaceAll("\\s+", " ");

            // --- TRATAMENTO DE DATA E HORA ---
            String[] dataHoraSplit = dataHoraStr.split("T");
            LocalDate data = LocalDate.parse(dataHoraSplit[0]);

            // Normaliza a hora: substitui '-' por ':' apenas se for o formato novo
            // Ex: 05-46-28 vira 05:46:28. Se já for 05:46:28, permanece igual.
            String horaNormalizada = dataHoraSplit[1].replace("-", ":");
            if (horaNormalizada.contains(".")) {
                horaNormalizada = horaNormalizada.split("\\.")[0];
            }
            LocalTime hora = LocalTime.parse(horaNormalizada);

            // --- TRATAMENTO DE RODOVIA E KM (Lógica Híbrida) ---
            String rodovia;
            String km = ""; // Campo vazio conforme seu log de exemplo

            // Verifica se contém indicadores do padrão da pasta /radar
            String localizacaoUpper = localizacaoBruta.toUpperCase();
            if (localizacaoUpper.contains("KM:") || localizacaoUpper.contains("METROS:")) {

                // PADRÃO /radar: Mantém a string completa conforme solicitado
                // Ex: "SPA-159/225 km: 008 Metros: 470" ou "Rodovia: SP-294 km: 543..."
                rodovia = localizacaoBruta.replaceAll("(?i)Rodovia:\\s*", "").trim();
                km = "0";

                log.debug("Processando padrão Radar: {}", rodovia);
            } else {
                // PADRÃO /recebidos: Mantém o comportamento original (P6 - Piracicaba, etc)
                rodovia = localizacaoBruta;
                km = (dados.length > 4) ? dados[4].trim() : "";
            }

            // 3. SEGURANÇA PARA O BANCO (Truncar apenas se exceder um limite razoável, ex: 100)
            // Isso evita que o erro de VARCHAR interrompa o processamento
            if (rodovia.length() > 100) {
                rodovia = rodovia.substring(0, 100);
            }

            // BUSCA NO CACHE (Sem bater no banco de dados)
            LocalizacaoRadar localizacaoDoRadar = localizacaoCache.get(gerarChaveCache(rodovia, km));

            if (localizacaoDoRadar == null) {
                log.debug("Localização não encontrada no cache para: {}", rodovia);
            }

            return new Radars(data, hora, placa, rodovia, km, sentido, localizacaoDoRadar);
        } catch (Exception e) {
            log.error("Erro no parsing da linha: {}. Causa: {}", linha, e.getMessage());
            return null;
        }
    }

    private String extrairRodoviaNovoPadrao(String texto) {
        // Busca por algo como SP-294 ou BR-153
        Pattern p = Pattern.compile("([A-Z]{2}-\\d{3})", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(texto);
        if (m.find()) {
            return m.group(1).toUpperCase();
        }
        return texto.split("km:")[0].replace("Rodovia:", "").trim();
    }

    private String extrairKmNovoPadrao(String texto) {
        // Busca o número logo após "km:"
        Pattern p = Pattern.compile("km:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(texto);
        return m.find() ? m.group(1) : "";
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
            // Tenta padrão com hifen: 2025-07-28
            Pattern patternHifen = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
            Matcher matcherHifen = patternHifen.matcher(nomeArquivo);
            if (matcherHifen.find()) {
                return Optional.of(LocalDate.parse(matcherHifen.group(1)));
            }

            // Tenta padrão colado: 20260215
            Pattern patternColado = Pattern.compile("(\\d{8})");
            Matcher matcherColado = patternColado.matcher(nomeArquivo);
            if (matcherColado.find()) {
                return Optional.of(LocalDate.parse(matcherColado.group(1), DateTimeFormatter.ofPattern("yyyyMMdd")));
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