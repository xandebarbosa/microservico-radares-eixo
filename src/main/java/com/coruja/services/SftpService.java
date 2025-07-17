package com.coruja.services;

import com.coruja.entities.LocalizacaoRadar;
import com.coruja.entities.Radars;
import com.coruja.repositories.LocalizacaoRadarRepository;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.apache.commons.net.ftp.FTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SftpService {

    private static final Logger logger = LoggerFactory.getLogger(SftpService.class);

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
    @Value("${sftp.schedule.rate.ms}")
    private long scheduleRateMs;

    private LocalDateTime lastExecutionTime;

    private final RadarsService radarsService;
    private final LocalizacaoRadarRepository localizacaoRadarRepository;

    @Autowired
    public SftpService(RadarsService radarsService, LocalizacaoRadarRepository localizacaoRadarRepository) {
        this.radarsService = radarsService;
        this.localizacaoRadarRepository = localizacaoRadarRepository;
    }

    @Scheduled(fixedRateString = "${sftp.schedule.rate.ms}")
    public void processarSftpEixo() {

        lastExecutionTime = LocalDateTime.now();
        //LocalDateTime horaInicio = LocalDateTime.now();
        logger.info("Iniciando verificação de arquivos no SFTP (Eixo) às {}...", lastExecutionTime);

        JSch jsch = new JSch();
        Session session = null;
        ChannelSftp sftpChannel = null;

        FTPClient ftpClient = new FTPClient();

        try {
            // Lógica de conexão encapsulada
            session = conectarSessaoSftp();
            sftpChannel = abrirCanalSftp(session);
            sftpChannel.cd(remoteDirectory);

            Path localPath = Path.of(localDirectory);
            Files.createDirectories(localPath);

            // Lógica de listagem e filtragem otimizada
            Set<String> arquivosLocais = listarArquivosLocais(localPath);
            Vector<ChannelSftp.LsEntry> arquivosRemotos = sftpChannel.ls(".");

            if (arquivosRemotos.isEmpty()) {
                logger.info("Nenhum arquivo encontrado no diretório do SFTP.");
                return;
            }

            // ADAPTADO: Lógica de filtro por data para o formato do Eixo
            LocalDate dataLimite = LocalDate.now().minusDays(1); // Para testes, usando 10 dias
            logger.info("Definida data limite para processamento: {}", dataLimite.format(DateTimeFormatter.ISO_LOCAL_DATE));

            List<ChannelSftp.LsEntry> novosArquivos = arquivosRemotos.stream()
                    .filter(entry -> !entry.getAttrs().isDir() && !entry.getFilename().startsWith(".")) // Ignora diretórios e arquivos ocultos
                    .filter(entry -> !arquivosLocais.contains(entry.getFilename()))
                    .filter(entry -> isDentroDoPeriodo(entry.getFilename(), dataLimite))
                    .collect(Collectors.toList());

            if (novosArquivos.isEmpty()) {
                logger.warn("FILTRAGEM COMPLETA: Nenhum arquivo novo foi encontrado dentro do período para processar.");
                return;
            }

            logger.info("NOVOS ARQUIVOS PARA PROCESSAR: {}", novosArquivos.stream().map(ChannelSftp.LsEntry::getFilename).collect(Collectors.toList()));
            List<Radars> todosOsRadares = new ArrayList<>();

            for (ChannelSftp.LsEntry entry : novosArquivos) {
                baixarArquivo(sftpChannel, entry.getFilename(), localPath).ifPresent(arquivoLocal -> {
                    logger.info("Processando o conteúdo do arquivo: {}", entry.getFilename());
                    List<Radars> radaresDoArquivo = processarArquivoLocal(arquivoLocal);
                    logger.info("Arquivo '{}' continha {} registros válidos.", entry.getFilename(), radaresDoArquivo.size());
                    todosOsRadares.addAll(radaresDoArquivo);
                });
            }

            if (!todosOsRadares.isEmpty()) {
                logger.info("SALVANDO NO BANCO: {} novos registros de radares.", todosOsRadares.size());
                radarsService.saveRadars(todosOsRadares);
                logger.info("Banco de dados atualizado com sucesso.");
            } else {
                logger.warn("Nenhum registro válido foi extraído dos novos arquivos processados.");
            }

        } catch (Exception e) {
            logger.error("ERRO CRÍTICO durante o processamento do SFTP: ", e);
        } finally {
            desconectarSftp(sftpChannel, session);
//            LocalDateTime proximaExecucao = horaInicio.plus(scheduleRateMs, ChronoUnit.MILLIS);
//            logger.info("Processo finalizado. Próxima execução agendada para: {}",
//                    proximaExecucao.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
//            logger.info("*******************************************\n");
            logger.info("*** Processamento finalizado ***");
            logger.info("*** Proxima execução em: 05:00");
        }
    }

    @Scheduled(fixedDelay = 1000) // Atualiza a cada segundo
    public void updateCountdown() {
        if (lastExecutionTime != null) {
            long secondsRemaining = 300 - ChronoUnit.SECONDS.between(lastExecutionTime, LocalDateTime.now());
            if (secondsRemaining > 0) {
                System.out.printf("\rPróxima execução em: %02d:%02d",
                        secondsRemaining / 60,
                        secondsRemaining % 60);
            } else {
                System.out.print("\rPróxima execução em: 00:00 - Iniciando...");
            }
        }
    }

    // LÓGICA MANTIDA E REFINADA: Parsing específico para o formato do Eixo (delimitado por ';')
    private Radars parseLine(String linha) {
        // Usa split por ';', que é mais seguro que por espaços
        String[] dados = linha.split(";", -1); // -1 para incluir campos vazios no final

        if (dados.length < 5) {
            logger.warn("Linha ignorada: número de colunas insuficiente (esperado >= 5, encontrado {}). Linha: '{}'", dados.length, linha);
            return null;
        }

        try {
            String dataHoraStr = dados[0].trim();
            String placa = tratarPlaca(dados[1].trim()); // Lógica de tratamento de placa específica do Eixo
            String praca = dados[2].trim().replaceAll("\\s+", " ");
            String sentido = dados[3].trim().replaceAll("\\s+", " ");

            String[] dataHoraSplit = dataHoraStr.split("T");
            if (dataHoraSplit.length != 2) {
                logger.warn("Formato de data/hora inválido (esperado 'yyyy-MM-ddTHH:mm:ss'), ignorando linha: '{}'", linha);
                return null;
            }

            LocalDate data = LocalDate.parse(dataHoraSplit[0], DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            LocalTime hora = LocalTime.parse(dataHoraSplit[1], DateTimeFormatter.ofPattern("HH:mm:ss"));

            String rodovia = praca;
            String km = "";

            // 1. Busca na tabela "De-Para" pelo objeto de localização completo.
            LocalizacaoRadar localizacaoDoRadar = localizacaoRadarRepository.findByRodoviaAndKm(rodovia, km)
                    .orElse(null);

            if (localizacaoDoRadar == null) {
                logger.warn("Não foi encontrada uma localização cadastrada para a rodovia: '{}'", rodovia);
            }

            // Assumindo que a entidade Radars pode ser criada sem rodovia e km, ou eles podem ser nulos/vazios.
            return new Radars(data, hora, placa, praca, rodovia, km, sentido, localizacaoDoRadar);
        } catch (Exception e) {
            logger.error("Erro fatal ao converter dados da linha: '{}'. Causa: {}", linha, e.getMessage());
            return null;
        }
    }

    // LÓGICA MANTIDA: Tratamento de placa específico deste serviço
    private String tratarPlaca(String placa) {
        if (placa == null || placa.isBlank()) {
            return "N/I"; // Retorna um valor padrão para placas vazias
        }
        placa = placa.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
        return placa.length() > 7 ? placa.substring(0, 7) : placa;
    }

    // ADAPTADO: Lógica de extração de data para o formato yyyyMMdd
    private Optional<LocalDate> extrairDataDoNome(String nomeArquivo) {
        try {
            Pattern pattern = Pattern.compile("(\\d{8})"); // Encontra uma sequência de 8 dígitos
            Matcher matcher = pattern.matcher(nomeArquivo);
            if (matcher.find()) {
                return Optional.of(LocalDate.parse(matcher.group(1), DateTimeFormatter.ofPattern("yyyyMMdd")));
            }
        } catch (DateTimeParseException e) {
            logger.warn("Não foi possível parsear a data do nome do arquivo '{}': {}", nomeArquivo, e.getMessage());
        }
        return Optional.empty();
    }

    // NOVO: Métodos auxiliares para organizar o código
    private boolean isDentroDoPeriodo(String nomeArquivo, LocalDate dataLimite) {
        return extrairDataDoNome(nomeArquivo)
                .map(dataArquivo -> !dataArquivo.isBefore(dataLimite))
                .orElse(false); // Se não conseguir extrair a data, ignora o arquivo
    }

    private List<Radars> processarArquivoLocal(Path arquivoLocal) {
        try (Stream<String> lines = Files.lines(arquivoLocal, StandardCharsets.UTF_8)) {
            return lines.map(this::parseLine).filter(Objects::nonNull).collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Falha ao ler o arquivo local: {}", arquivoLocal, e);
            return Collections.emptyList();
        }
    }

    private Session conectarSessaoSftp() throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(sftpUser, sftpHost, sftpPort);
        session.setPassword(sftpPass);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();
        logger.info("Sessão SFTP conectada com sucesso.");
        return session;
    }

    private ChannelSftp abrirCanalSftp(Session session) throws Exception {
        ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
        sftpChannel.connect();
        logger.info("Canal SFTP aberto com sucesso.");
        return sftpChannel;
    }

    private void desconectarSftp(ChannelSftp channel, Session session) {
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        logger.info("Conexão SFTP encerrada.");
    }

    private Optional<Path> baixarArquivo(ChannelSftp sftpChannel, String nomeArquivo, Path diretorioLocal) {
        Path arquivoLocal = diretorioLocal.resolve(nomeArquivo);
        try (InputStream inputStream = sftpChannel.get(nomeArquivo)) {
            Files.copy(inputStream, arquivoLocal, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(arquivoLocal);
        } catch (Exception e) {
            logger.error("Erro ao baixar arquivo {}: ", nomeArquivo, e);
            return Optional.empty();
        }
    }

    private Set<String> listarArquivosLocais(Path diretorioLocal) {
        if (!Files.exists(diretorioLocal)) return Collections.emptySet();
        try (Stream<Path> stream = Files.list(diretorioLocal)) {
            return stream.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        } catch (IOException e) {
            logger.warn("Não foi possível listar arquivos locais. Downloads podem ser repetidos.", e);
            return Collections.emptySet();
        }
    }
}
