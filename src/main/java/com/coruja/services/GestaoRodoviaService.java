package com.coruja.services;

import com.coruja.dto.KmRodoviaDTO;
import com.coruja.entities.KmRodovia;
import com.coruja.entities.Rodovia;
import com.coruja.repositories.KmRodoviaRepository;
import com.coruja.repositories.RadarsRepository;
import com.coruja.repositories.RodoviaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class GestaoRodoviaService {

    private final RodoviaRepository rodoviaRepository;
    private final KmRodoviaRepository kmRodoviaRepository;
    private final RadarsRepository radarsRepository;

    //Cache Thread-safe
    private final ConcurrentHashMap<String, Rodovia> rodoviaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> kmCachePorRodovia = new ConcurrentHashMap<>();

    /**
     * Lista todas as rodovias cadastradas.
     * Utiliza o Redis (cache 'lista-rodovias') para evitar ir ao banco repetidamente.
     */
    @Cacheable(value = "lista-rodovias")
    public List<Rodovia> listarRodovias() {
        log.info("🚚 Cache de rodovias vazio, carregando do banco de dados Eixo...");
        if (rodoviaCache.isEmpty()) {
            rodoviaRepository.findAll().forEach(r -> rodoviaCache.putIfAbsent(r.getNome(), r));
        }
        return new ArrayList<>(rodoviaCache.values());
    }

    /**
     * Salva uma rodovia individualmente.
     * Limpa o cache para que a próxima listagem pegue o dado novo.
     */
    @Transactional
    @CacheEvict(value = "lista-rodovias", allEntries = true)
    public Rodovia salvarRodovia(Rodovia rodovia) {
        if (rodoviaRepository.existsByNome(rodovia.getNome())) {
            throw new IllegalArgumentException("Rodovia '" + rodovia.getNome() + "' já existe.");
        }
        log.info("💾 Salvando nova rodovia Concessionária EixoArqu: {}", rodovia.getNome());
        Rodovia salva = rodoviaRepository.save(rodovia);
        rodoviaCache.put(salva.getNome(), salva);
        return salva;
    }

    @Transactional
    @CacheEvict(value = "lista-rodovias", allEntries = true)
    public void deletarRodovia(Long id) {
        rodoviaRepository.deleteById(id);
    }

    /**
     * ✅ Busca os KMs diretamente da tabela de domínio que já está populada.
     * Trocamos o nome do cache para "lista-kms-oficial" para forçar o Redis
     * a descartar qualquer array vazio [] que tenha ficado preso no cache antigo.
     */
    @Cacheable(value = "lista-kms-oficial", key = "#rodoviaId")
    public List<KmRodoviaDTO> listarKmsPorRodovia(Long rodoviaId) {
        log.info("📍 Buscando KMs na tabela de domínio para a rodovia ID: {}", rodoviaId);

        // 1. Busca diretamente da tabela kms_rodovia que já está populada (rápido e direto)
        List<KmRodovia> kms = kmRodoviaRepository.findByRodoviaId(rodoviaId);

        // 2. Converte para DTO, filtra sujeiras da pasta /recebidos e ordena
        return kms.stream()
                // Garante que não vai mandar KMs vazios ou nulos pro Front-end
                .filter(km -> km.getValor() != null && !km.getValor().trim().isEmpty())
                .map(km -> KmRodoviaDTO.builder()
                        .id(km.getId())
                        .valor(km.getValor().trim())
                        .rodoviaId(rodoviaId)
                        .build())
                // Ordena os KMs (ex: 100+000 aparecerá antes de 200+000)
                .sorted(Comparator.comparing(KmRodoviaDTO::getValor))
                .collect(Collectors.toList());
    }

    /**
     * Salva Kms por rodovia.
     */
    @Transactional
    @CacheEvict(value = "lista-kms", key = "#km.rodovia.id")
    public KmRodovia salvarKm(KmRodovia km) {
        return kmRodoviaRepository.save(km);
    }

    /**
     * Deleta Kms por rodovia.
     */
    @Transactional
    @CacheEvict(value = "lista-kms", allEntries = true)
    public void deletarKm(Long id) {
        kmRodoviaRepository.deleteById(id);
    }

    // Método auxiliar de conversão
    private KmRodoviaDTO toDTO(KmRodovia entity) {
        return new KmRodoviaDTO(
                entity.getId(),
                entity.getValor(),
                entity.getRodovia().getId()
        );
    }

    /**
     * ✅ APRENDIZADO EM LOTE OTIMIZADO
     * 1. Recebe apenas a lista de nomes (List<String>), corrigindo o erro de tipo.
     * 2. Busca todas as rodovias existentes de uma vez.
     * 3. Filtra apenas as que são realmente novas.
     * 4. Salva em lote (saveAll), reduzindo drasticamente o tempo de conexão com o banco.
     */
    @Transactional
    @CacheEvict(value = {"lista-rodovias", "lista-kms"}, allEntries = true)
    public void registrarDescobertas(Map<String, Set<String>> descobertas) {
        if (descobertas == null || descobertas.isEmpty()) return;

        log.info("🧠 Aprendizado de domínio: Processando {} rodovias Concessionária Eixo...", descobertas.size());

        if (rodoviaCache.isEmpty()) {
            rodoviaRepository.findAll().forEach(r -> rodoviaCache.put(r.getNome(), r));
        }

        // Remove duplicatas da lista recebida (ex: 50 registros da mesma rodovia no arquivo)
        //Set<String> nomesUnicos = new HashSet<>(descobertas);

        //log.info("🧠 Aprendizado de domínio: Analisando {} nomes únicos...", nomesUnicos.size());

        List<KmRodovia> novosKms = new ArrayList<>();

        descobertas.forEach((String nomeRodovia, Set<String> listaKms) -> {
            // Garante a Rodovia
            Rodovia rodovia = rodoviaCache.computeIfAbsent(nomeRodovia, nome -> {
                log.info("🆕 Registrando nova Rodovia, Concessionária Eixo: {}", nome);
                return rodoviaRepository.save(Rodovia.builder().nome(nome).build());
            });

            // Garante os KMs
            Set<String> kmsExistentes = kmCachePorRodovia.computeIfAbsent(rodovia.getId(), id ->
                    kmRodoviaRepository.findByRodoviaId(id).stream()
                            .map(KmRodovia::getValor)
                            .collect(Collectors.toCollection(HashSet::new))
            );

            for (String valorKm : listaKms) {
                synchronized (kmsExistentes) {
                    if (kmsExistentes.add(valorKm)) {
                        novosKms.add(KmRodovia.builder().valor(valorKm).rodovia(rodovia).build());
                    }
                }
            }
        });

        // 3. Persistência em lote (Batch) para performance
        if (!novosKms.isEmpty()) {
            kmRodoviaRepository.saveAll(novosKms);
            log.info("✅ Sucesso: {} novos KMs cadastrados no domínio Eixo.", novosKms.size());
        }

    }
}
