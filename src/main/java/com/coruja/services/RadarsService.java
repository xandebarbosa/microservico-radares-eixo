package com.coruja.services;


import com.coruja.dto.*;
import com.coruja.entities.Radars;
import com.coruja.enuns.Sentido;
import com.coruja.repositories.LocalizacaoRadarRepository;
import com.coruja.repositories.RadarsRepository;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service // Anotação que marca esta classe como um serviço do Spring, permitindo a injeção de dependências automaticamente.
@Slf4j
public class RadarsService {

    // NOVO: Chave de roteamento específica para este serviço (EIXO)
    private static final String ROUTING_KEY = "radares.eixo";

    @Value("${rabbitmq.exchange.name}")
    private String exchangeName;

    @Value("${rabbitmq.routing.key}")
    private String routingKey;

    // Injeção de dependências via construtor (melhor prática)
    private final RadarsRepository radarsRepository;
    private final RabbitTemplate rabbitTemplate;
    private final LocalizacaoRadarRepository localizacaoRadarRepository;

    // Thread Pool para tarefas assíncronas (RabbitMQ e Cache)
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    // ✅ Cache thread-safe para metadados frequentes (ex: nomes de praças)
    private final ConcurrentHashMap<String, String> normalizeCache = new ConcurrentHashMap<>();


    // Construtor que recebe o repositório como parâmetro e o atribui à variável de instância.
    // Isso permite a injeção de dependência via construtor.
    public RadarsService(
            RadarsRepository radarsRepository,
            RabbitTemplate rabbitTemplate,
            LocalizacaoRadarRepository localizacaoRadarRepository
    ) {
        this.radarsRepository = radarsRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.localizacaoRadarRepository = localizacaoRadarRepository;
    }

    /**
     * Busca ESPECÍFICA por placa.
     */
    @Transactional(readOnly = true)
    public Page<RadarsDTO> buscarPorPlaca(String placa, Pageable pageable) {
        if (placa == null || placa.isBlank()) {
            throw new IllegalArgumentException("O parâmetro 'placa' é obrigatório.");
        }
        return radarsRepository.findAllByPlaca(normalize(placa), pageable)
                .map(this::converterParaDTO);
    }

    /**
     * Busca por LOCAL
     * Método UNIFICADO para buscar radares com filtros dinâmicos e opcionais.
     *
     * @param data        Data do registro
     * @param horaInicial Hora inicial do intervalo
     * @param horaFinal   Hora final do intervalo
     * @param rodovia     Local da rodovia
     * @param km          Quilômetro da rodovia
     * @param sentido     Sentido da via
     * @param pageable    Informações de paginação
     * @return Uma página de RadarsDTO que corresponde aos filtros.
     */
    @Transactional(readOnly = true)
    public RadarPageDTO buscarPorLocal(
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal,
            String rodovia,
            String km,
            String sentido,
            Pageable pageable
    ) {
        log.info("=== INÍCIO BUSCA POR LOCAL - EIXO ===");
        log.info("📅 Data: {}", data);
        log.info("🕐 Hora Inicial: {}", horaInicial);
        log.info("🕑 Hora Final: {}", horaFinal);
        log.info("🛣️  Rodovia (ANTES normalização): '{}'", rodovia);
        log.info("📍 KM (ANTES normalização): '{}'", km);
        log.info("➡️  Sentido (ANTES normalização): '{}'", sentido);

        // Normalização com logs
        String rodoviaProcessada = normalize(rodovia);
        String kmProcessado = normalize(km);
        String sentidoProcessado = normalize(sentido);

        log.info("🛣️  Rodovia (DEPOIS normalização): '{}'", rodoviaProcessada);
        log.info("📍 KM (DEPOIS normalização): '{}'", kmProcessado);
        log.info("➡️  Sentido (DEPOIS normalização): '{}'", sentidoProcessado);

        log.info("🔎 Executando query no Banco de Dados...");

        Page<Radars> page = radarsRepository.findByLocalFilter(
                data,
                horaInicial,
                horaFinal,
                normalize(rodovia),
                normalize(km),
                sentido,
                pageable
        );
        log.info("✅ Query executada. Resultados encontrados: {}", page.getTotalElements());
        log.info("📄 Itens na página atual: {}", page.getNumberOfElements());
        log.info("=== FIM BUSCA POR LOCAL - EIXO ===");

        return convertToPageDTO(page);
    }

    /**
     * ✅ BUSCA GEOESPACIAL OTIMIZADA
     */
    @Transactional(readOnly = true)
    @Timed(value = "radares.busca.geo", histogram = true)
    public Page<RadarsDTO> buscarPorGeolocalizacao(
            Double latitude, Double longitude, Double raio,
            LocalDate data, LocalTime horaInicio, LocalTime horaFim,
            Pageable pageable) {

        if (latitude == null || longitude == null || data == null) {
            throw new IllegalArgumentException("Latitude, Longitude e Data são obrigatórios");
        }

        double raioMetros = (raio != null) ? raio : 15000.0;

        Page<Radars> resultado = radarsRepository.findByLocalizacaoFilter(
                latitude, longitude, raioMetros, data, horaInicio, horaFim, pageable
        );

        return resultado.map(this::converterParaDTO);
    }

    /**
     * ✅ LOCALIZAÇÕES PARA MAPA - Cache de 24 horas
     */
    @Cacheable(
            value = "mapa-radares-eixo",
            unless = "#result == null || #result.isEmpty()"
    )
    @Transactional(readOnly = true)
    public List<LocalizacaoRadarProjection> listarTodasLocalizacoes() {
        return localizacaoRadarRepository.findAllLocations();
    }



    /**
     * Salva as leituras dos radares e publica as placas detectadas no RabbitMQ
     * de forma resiliente.
     */
    @Transactional
    public void saveRadars(List<Radars> radarsList) {
        if (radarsList == null || radarsList.isEmpty()) {
            return;
        }

        // 1. Salva todas as entidades e captura a lista de entidades salvas.
        //    A lista 'savedRadars' agora contém as entidades com os IDs preenchidos.
        // 1. A operação principal e mais importante: salvar no banco.
        List<Radars> savedRadars = radarsRepository.saveAll(radarsList);
        log.info("{} registros salvos no banco de dados com sucesso - Concessionária Eixo.", savedRadars.size());

        //Publica no RabbitMQ de forma assincrona
        CompletableFuture.runAsync(() ->
                        savedRadars.forEach(this::enviarMensagemParaRabbitMQ),
                executorService
        );
    }

    // ==================== MÉTODOS AUXILIARES ====================

    /**
     * ✅ LIMPEZA DE CACHE PROGRAMADA
     * Roda às 3:00 AM todos os dias
     */
    @Scheduled(cron = "0 0 3 * * *")
    @CacheEvict(value = {"radars-search", "radars-placa", "opcoes-filtro-eixo", "lista-rodovias"}, allEntries = true)
    public void limparCacheDiario() {
        log.info("🧹 Limpeza diária de cache executada");
    }

    /**
     * NOVO: Método auxiliar para encapsular a lógica de envio e o tratamento de erro.
     * @param radar O objeto radar para o qual a mensagem será enviada.
     */
    private void enviarMensagemParaRabbitMQ(Radars radar) {
        if (!isValidRadar(radar)) { // Lógica de validação em um método auxiliar
            log.warn("Dados incompletos para a placa: {}. Mensagem não será enviada.", radar.getPlaca());
            return;
        }

        LocalDateTime dataHoraRadar = LocalDateTime.of(radar.getData(), radar.getHora());
        LocalDateTime limite = LocalDateTime.now().minusHours(5);

        if (dataHoraRadar.isBefore(limite)) {
            return; // Ignora dados antigos
        }

        try {
            String mensagem = formatMessage(radar);
            rabbitTemplate.convertAndSend(exchangeName, routingKey, mensagem);
            log.info("Mensagem enviada para RabbitMQ com routingKey [{}]: {} - Concessionária Eixo", routingKey, mensagem);
        } catch (AmqpException e) {
            // Tratamento de erro resiliente
            log.warn("Falha ao enviar mensagem para RabbitMQ - Placa: {}. Causa: {} - Concessionária Eixo", radar.getPlaca(), e.getMessage());
        }
    }

    // Métodos auxiliares para manter o código limpo
    private boolean isValidRadar(Radars radar) {
        return radar != null && radar.getData() != null && radar.getHora() != null && radar.getPlaca() != null;
    }

    private String formatMessage(Radars radar) {
        // Extrai o nome da concessionária da routing key (ex: 'radares.eixo' -> 'EIXO')
        String concessionaria = routingKey.split("\\.")[1].toUpperCase();
        return String.format("%s|%s|%s|%s|%s|%s|%s|%s",
                concessionaria, radar.getData(), radar.getHora(), radar.getPlaca(),
                radar.getRodovia(), radar.getRodovia(), radar.getKm(), radar.getSentido());
    }

    /**
     * ✅ NORMALIZAÇÃO CORRIGIDA COM URL DECODING
     * Remove espaços extras, converte para maiúsculas, decodifica URL
     * Retorna null se input for null ou vazio
     */
    private String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        return normalizeCache.computeIfAbsent(input, i -> {
            try {
                // ✅ 1. DECODIFICA URL primeiro (P5%20-%20Jau → P5 - Jau)
                String decoded = URLDecoder.decode(i, StandardCharsets.UTF_8);

                // ✅ 2. NORMALIZA (trim + uppercase)
                String normalized = decoded.trim().toUpperCase();

                log.debug("Normalizado: '{}' -> '{}' -> '{}'", i, decoded, normalized);
                return normalized;
            } catch (Exception e) {
                // Fallback se houver erro na decodificação
                String normalized = i.trim().toUpperCase();
                log.warn("Erro ao decodificar '{}', usando sem decode: '{}'", i, normalized);
                return normalized;
            }
        });
    }

    /**
     * Converte Page<Entity> para RadarPageDTO (Estrutura paginada para JSON)
     */
    private RadarPageDTO convertToPageDTO(Page<Radars> page) {
        List<RadarsDTO> content = page.getContent().stream()
                .map(this::converterParaDTOBuscaLocal) // ✅ Reutiliza o conversor centralizado
                .collect(Collectors.toList());

        PageMetadata metadata = new PageMetadata(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );

        return new RadarPageDTO(content, metadata);
    }

    private RadarsDTO converterParaDTOBuscaLocal(Radars radars) {
        RadarsDTO dto = new RadarsDTO();
        dto.setId(radars.getId());
        dto.setData(radars.getData());
        dto.setHora(radars.getHora());
        dto.setPlaca(radars.getPlaca());
        dto.setRodovia(radars.getRodovia());
        dto.setKm(radars.getKm());

        // Conversão Segura de String -> Enum
        try {
            dto.setSentido(Sentido.fromString(radars.getSentido()));
        } catch (Exception e) {
            dto.setSentido(Sentido.NAO_IDENTIFICADO);
        }

        return dto;
    }

    private RadarsDTO converterParaDTO(Radars radars) {
        return RadarsDTO.builder()
                .id(radars.getId())
                .data(radars.getData())
                .hora(radars.getHora())
                .placa(radars.getPlaca())
                .rodovia(radars.getRodovia())
                .km(radars.getKm())
                .sentido(Sentido.fromString(radars.getSentido()))
                .build();
    }
}
