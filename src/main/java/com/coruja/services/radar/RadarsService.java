package com.coruja.services.radar;


import com.coruja.dto.*;
import com.coruja.entities.Radars;
import com.coruja.enuns.Sentido;
import com.coruja.enuns.TipoFonte;
import com.coruja.repositories.LocalizacaoRadarRepository;
import com.coruja.repositories.RadarsRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
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
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service // Anotação que marca esta classe como um serviço do Spring, permitindo a injeção de dependências automaticamente.
@Slf4j
@RequiredArgsConstructor
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
    private final Executor virtualThreadExecutor;

    /** Cache de normalização de strings para evitar reprocessamento. */
    private final ConcurrentHashMap<String, String> normalizeCache = new ConcurrentHashMap<>(512);

    /**
     * Busca ESPECÍFICA por placa: Retorna histórico completo.
     */
    @Transactional(readOnly = true)
    @Timed(value = "radares.busca.placa", histogram = true)
    public Page<RadarsDTO> buscarPorPlaca(String placa, Pageable pageable) {
        if (placa == null || placa.isBlank()) {
            throw new IllegalArgumentException("O parâmetro 'placa' é obrigatório.");
        }
        return radarsRepository
                .findAllByPlaca(normalize(placa), pageable)
                .map(this::toDTO);
    }

    // ─────────────────────────────────────────────────────────────
    // BUSCA POR LOCAL  —  roteada por TipoFonte
    // ─────────────────────────────────────────────────────────────

    /**
     * Busca unificada com roteamento por {@link TipoFonte}.
     *
     * <ul>
     *   <li>{@code RECEBIDOS} → query sem filtro de KM (pasta /recebidos)</li>
     *   <li>{@code RADAR}     → query com filtro de KM (pasta /radar)</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    @Timed(value = "radares.busca.local", histogram = true)
    public RadarPageDTO buscarPorLocal(BuscaLocalRequest req, Pageable pageable) {
        log.info("[Eixo] buscarPorLocal | data={} | rodovia='{}' | km='{}' | sentido='{}'",
                req.getData(), req.getRodovia(), req.getKm(), req.getSentido());

        // Usamos a query unificada que procura em TUDO (Radar e Recebidos)
        Page<Radars> page = radarsRepository.findByLocalFilter(
                req.getData(),
                req.getHoraInicial(),
                req.getHoraFinal(),
                normalize(req.getRodovia()),
                normalize(req.getKm()),
                normalize(req.getSentido()),
                pageable
        );

        log.info("[Eixo] Resultado: {} registros (página {})", page.getTotalElements(), page.getNumber());
        return toPageDTO(page);
    }

    // ─────────────────────────────────────────────────────────────
    // BUSCA GEOESPACIAL
    // ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @Timed(value = "radares.busca.geo", histogram = true)
    public Page<RadarsDTO> buscarPorGeolocalizacao(
            Double latitude, Double longitude, Double raio,
            LocalDate data, LocalTime horaInicio, LocalTime horaFim,
            Pageable pageable) {

        if (latitude == null || longitude == null || data == null) {
            throw new IllegalArgumentException("Latitude, longitude e data são obrigatórios.");
        }

        double raioMetros = (raio != null) ? raio : 15_000.0;

        return radarsRepository
                .findByLocalizacaoFilter(latitude, longitude, raioMetros, data, horaInicio, horaFim, pageable)
                .map(this::toDTO);
    }

    // ─────────────────────────────────────────────────────────────
    // MAPA
    // ─────────────────────────────────────────────────────────────

    @Cacheable(value = "mapa-radares-eixo", unless = "#result == null || #result.isEmpty()")
    @Transactional(readOnly = true)
    public List<LocalizacaoRadarProjection> listarTodasLocalizacoes() {
        return localizacaoRadarRepository.findAllLocations();
    }

    // ─────────────────────────────────────────────────────────────
    // PERSISTÊNCIA
    // ─────────────────────────────────────────────────────────────

    /**
     * Salva lote de radares e publica no RabbitMQ de forma assíncrona.
     */
    @Transactional
    public void saveRadars(List<Radars> radarsList) {
        if (radarsList == null || radarsList.isEmpty()) return;

        List<Radars> saved = radarsRepository.saveAll(radarsList);
        log.info("[Eixo] {} registros salvos (fonte: {}).",
                saved.size(),
                radarsList.getFirst().getTipoFonte());

        CompletableFuture.runAsync(
                () -> saved.forEach(this::enviarParaRabbitMQ),
                virtualThreadExecutor
        );
    }

    // ─────────────────────────────────────────────────────────────
    // AUXILIARES PRIVADOS
    // ─────────────────────────────────────────────────────────────

    private void enviarParaRabbitMQ(Radars radar) {
        if (!isValid(radar)) return;

        LocalDateTime dataHora = LocalDateTime.of(radar.getData(), radar.getHora());
        if (dataHora.isBefore(LocalDateTime.now().minusHours(5))) return; // dados antigos ignorados

        try {
            String msg = formatarMensagem(radar);
            rabbitTemplate.convertAndSend(exchangeName, routingKey, msg);
        } catch (AmqpException e) {
            log.warn("[Eixo] Falha RabbitMQ — placa: {} | erro: {}", radar.getPlaca(), e.getMessage());
        }
    }

    private boolean isValid(Radars r) {
        return r != null && r.getData() != null && r.getHora() != null && r.getPlaca() != null;
    }

    private String formatarMensagem(Radars r) {
        String concessionaria = routingKey.split("\\.")[1].toUpperCase();
        return String.format("%s|%s|%s|%s|%s|%s|%s|%s",
                concessionaria, r.getData(), r.getHora(), r.getPlaca(),
                r.getRodovia(), r.getRodovia(), r.getKm(), r.getSentido());
    }

    private RadarPageDTO toPageDTO(Page<Radars> page) {
        List<RadarsDTO> content = page.getContent().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        PageMetadata metadata = new PageMetadata(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
        return new RadarPageDTO(content, metadata);
    }

    RadarsDTO toDTO(Radars r) {
        return RadarsDTO.builder()
                .id(r.getId())
                .data(r.getData())
                .hora(r.getHora())
                .placa(r.getPlaca())
                .rodovia(r.getRodovia())
                .km(r.getKm())
                .sentido(Sentido.fromString(r.getSentido()))
                .tipoFonte(r.getTipoFonte())
                .concessionaria("Eixo")
                .build();
    }

    /**
     * Normaliza string: URL-decode → trim → UPPER.
     * Retorna {@code null} se a entrada for nula ou vazia (para queries SQL tratarem como IS NULL).
     */
    String normalize(String input) {
        if (input == null || input.isBlank()) return null;

        return normalizeCache.computeIfAbsent(input, i -> {
            try {
                //return URLDecoder.decode(i, StandardCharsets.UTF_8).trim().toUpperCase();
                log.info("Normalizado: {}", input);
                return i.trim().toUpperCase();
            } catch (Exception e) {
                log.warn("[Eixo] Erro ao normalizar '{}': {}", i, e.getMessage());
                return i.trim().toUpperCase();
            }
        });
    }

    @Scheduled(cron = "0 0 3 * * *")
    @CacheEvict(value = {"radars-eixo-search", "radars-eixo-placa", "opcoes-filtro-eixo",
            "lista-rodovias", "mapa-radares-eixo"}, allEntries = true)
    public void limparCacheDiario() {
        normalizeCache.clear();
        log.info("[Eixo] Limpeza diária de cache concluída.");
    }

}
