package com.coruja.services;


import com.coruja.dto.FilterOptionsDTO;
import com.coruja.dto.RadarsDTO;
import com.coruja.entities.Radars;
import com.coruja.repositories.RadarsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service // Anotação que marca esta classe como um serviço do Spring, permitindo a injeção de dependências automaticamente.
public class RadarsService {

    private static final Logger logger = LoggerFactory.getLogger(RadarsService.class);

    // NOVO: Chave de roteamento específica para este serviço (EIXO)
    private static final String ROUTING_KEY = "radares.eixo";

    @Value("${rabbitmq.exchange.name}")
    private String exchangeName;

    @Value("${rabbitmq.routing.key}")
    private String routingKey;

    // Injeção de dependências via construtor (melhor prática)
    private final RadarsRepository radarsRepository;
    private final RabbitTemplate rabbitTemplate;

    // Construtor que recebe o repositório como parâmetro e o atribui à variável de instância.
    // Isso permite a injeção de dependência via construtor.
    public RadarsService(RadarsRepository radarsRepository, RabbitTemplate rabbitTemplate) {
        this.radarsRepository = radarsRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Método UNIFICADO para buscar radares com filtros dinâmicos e opcionais.
     * Este método substitui getAllRadars, buscarPorPlaca e buscarPorLocal.
     *
     * @param placa       Placa do veículo (opcional)
     * @param praca       Praca da rodovia
     * @param rodovia     Nome da rodovia (opcional)
     * @param km          Quilômetro da rodovia (opcional)
     * @param sentido     Sentido da via (opcional)
     * @param data        Data do registro (opcional)
     * @param horaInicial Hora inicial do intervalo (opcional)
     * @param horaFinal   Hora final do intervalo (opcional)
     * @param pageable    Informações de paginação
     * @return Uma página de RadarsDTO que corresponde aos filtros.
     */

    public Page<RadarsDTO> buscarComFiltros(
            String placa, String praca, String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal, Pageable pageable
    ) {
        try {
            if (placa != null) placa = URLDecoder.decode(placa, StandardCharsets.UTF_8);
            if (praca != null) praca = URLDecoder.decode(praca, StandardCharsets.UTF_8);
            if (rodovia != null) rodovia = URLDecoder.decode(rodovia, StandardCharsets.UTF_8);
            if (km != null) km = URLDecoder.decode(km, StandardCharsets.UTF_8);
            if (sentido != null) sentido = URLDecoder.decode(sentido, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Erro ao decodificar parâmetros da URL. A busca pode falhar.", e);
        }

        String finalPlaca = placa;
        String finalPraca = praca;
        String finalRodovia = rodovia;
        String finalKm = km;
        String finalSentido = sentido;

        Specification<Radars> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Adiciona um predicado para cada parâmetro, APENAS se ele não for nulo/vazio
            if (finalPlaca != null && !finalPlaca.isBlank()) {
                // Compara a placa convertendo para minúsculas e removendo espaços
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("placa")),
                        finalPlaca.toLowerCase().trim()
                ));
            }
            if (finalPraca != null && !finalPraca.isBlank()) {
                // Compara a praça convertendo para minúsculas e removendo espaços
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("praca")),
                        finalPraca.toLowerCase().trim()
                ));
            }
            if (finalRodovia != null && !finalRodovia.isBlank()) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("rodovia")),
                        finalRodovia.toLowerCase().trim()
                ));
            }
            if (finalKm != null && !finalKm.isBlank()) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("km")),
                        finalKm.toLowerCase().trim()
                ));
            }
            if (finalSentido != null && !finalSentido.isBlank()) {
                // Compara o sentido convertendo para minúsculas e removendo espaços
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("sentido")),
                        finalSentido.toLowerCase().trim()
                ));
            }
            if (data != null) {
                predicates.add(criteriaBuilder.equal(root.get("data"), data));
            }
            if (horaInicial != null) {
                // Adiciona condição para hora >= horaInicial
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("hora"), horaInicial));
            }
            if (horaFinal != null) {
                // Adiciona condição para hora <= horaFinal
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("hora"), horaFinal));
            }

            return criteriaBuilder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return radarsRepository.findAll(spec, pageable).map(this::converterParaDTO);
    }

    /**
     * Busca ESPECÍFICA por placa.
     */
    @Transactional(readOnly = true)
    public Page<RadarsDTO> buscarApenasPorPlaca(String placa, Pageable pageable) {
        if (placa == null || placa.isBlank()) {
            throw new IllegalArgumentException("O parâmetro 'placa' é obrigatório.");
        }
        return radarsRepository.findByPlaca(placa, pageable).map(this::converterParaDTO);
    }


    /**
     * Método que busca registros de radares com base na placa do veículo.
     *
     * @param placa A placa do veículo a ser pesquisada.
     * @return Lista de objetos RadarsDTO contendo os dados dos radares que detectaram a placa informada.
     */
    @Transactional // Garante que a operação seja executada dentro de uma transação do banco de dados.
    public Page<RadarsDTO> buscarPorPlaca(String placa, Pageable pageable) {
        return radarsRepository.findByPlaca(placa, pageable)// Busca no repositório os registros correspondentes à placa fornecida.
                .map(this::converterParaDTO); // Converte cada entidade Radars para um DTO (Data Transfer Object).

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
        logger.info("{} registros salvos no banco de dados com sucesso.", savedRadars.size());

        // 2. Itera sobre a lista de entidades JÁ SALVAS para enviar ao RabbitMQ.
        savedRadars.forEach(this::enviarMensagemParaRabbitMQ);
    }

    /**
     * NOVO: Método auxiliar para encapsular a lógica de envio e o tratamento de erro.
     * @param radar O objeto radar para o qual a mensagem será enviada.
     */
    private void enviarMensagemParaRabbitMQ(Radars radar) {
        if (!isValidRadar(radar)) { // Lógica de validação em um método auxiliar
            logger.warn("Dados incompletos para a placa: {}. Mensagem não será enviada.", radar.getPlaca());
            return;
        }

        String mensagem = formatMessage(radar);

        try {
            rabbitTemplate.convertAndSend(exchangeName, routingKey, mensagem);
            logger.info("Mensagem enviada para RabbitMQ com routingKey [{}]: {}", routingKey, mensagem);
        } catch (AmqpException e) {
            // Tratamento de erro resiliente
            logger.warn("Falha ao enviar mensagem para RabbitMQ - Placa: {}. Causa: {}", radar.getPlaca(), e.getMessage());
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
                radar.getPraca(), radar.getRodovia(), radar.getKm(), radar.getSentido());
    }

    public FilterOptionsDTO getFilterOptions() {
        // Busca os dados das 4 fontes
        List<String> rodovias = radarsRepository.findDistinctRodovias();
        List<String> kms = radarsRepository.findDistinctKms();
        List<String> sentidos = radarsRepository.findDisntictSentidos();
        List<String> pracas = radarsRepository.findDistinctPracas(); // Busca as novas opções

        // Chama o construtor correto com 4 argumentos
        return new FilterOptionsDTO(rodovias, kms, sentidos, pracas);
    }

    public List<String> getKmsForRodovia(String rodovia) {
        if (rodovia == null || rodovia.isBlank()) {
            return new ArrayList<>(); // Retorna lista vazia se nenhuma rodovia for fornecida
        }
        return radarsRepository.findDistinctKmsByRodovia(rodovia);
    }

    /**
     * Método privado que converte uma entidade Radars para um DTO.
     *
     * @param radars Objeto Radars a ser convertido.
     * @return Objeto RadarsDTO contendo os mesmos dados da entidade Radars.
     */
    private RadarsDTO converterParaDTO(Radars radars) {
        return RadarsDTO.builder() // Utiliza o padrão Builder para construir um RadarsDTO.
                .id(radars.getId()) // Define o ID do radar.
                .data(radars.getData()) // Define a data de detecção.
                .hora(radars.getHora()) // Define o horário de detecção.
                .placa(radars.getPlaca()) // Define a placa do veículo.
                .praca(radars.getPraca()) // Define a praça de pedágio onde foi detectado.
                .rodovia(radars.getRodovia()) // Define a rodovia onde foi detectado.
                .km(radars.getKm()) // Define o quilômetro da rodovia onde foi detectado.
                .sentido(radars.getSentido()) // Define o sentido da rodovia onde foi detectado.
                .build(); // Retorna o objeto RadarsDTO construído.
    }
}
