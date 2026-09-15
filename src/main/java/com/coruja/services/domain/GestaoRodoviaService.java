package com.coruja.services.domain;

import com.coruja.dto.KmRodoviaDTO;
import com.coruja.entities.KmRodovia;
import com.coruja.entities.Rodovia;
import com.coruja.repositories.KmRodoviaRepository;
import com.coruja.repositories.RadarsRepository;
import com.coruja.repositories.RodoviaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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

    // Injetando CacheManager para invalidação programática condicional
    private final CacheManager cacheManager;

    //Cache Thread-safe
    private final ConcurrentHashMap<String, Rodovia> rodoviaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> kmCachePorRodovia = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────
    // LISTAGEM
    // ─────────────────────────────────────────────────────────────
    /**
     * Lista todas as rodovias cadastradas.
     * Utiliza o Redis (cache 'lista-rodovias') para evitar ir ao banco repetidamente.
     */
    @Cacheable("lista-rodovias")
    public List<Rodovia> listarRodovias() {
        log.info("[Domínio] Carregando rodovias do banco para o Redis...");
        List<Rodovia> lista = rodoviaRepository.findAll();
        lista.forEach(r -> rodoviaCache.putIfAbsent(r.getNome(), r));
        return lista;
    }
    /**
     * ✅ Busca os KMs diretamente da tabela de domínio que já está populada.
     * Trocamos o nome do cache para "lista-kms-oficial" para forçar o Redis
     * a descartar qualquer array vazio [] que tenha ficado preso no cache antigo.
     */
    @Cacheable(value = "lista-kms", key = "#rodoviaId")
    public List<KmRodoviaDTO> listarKmsPorRodovia(Long rodoviaId) {
        log.info("📍 Buscando KMs na tabela de domínio para a rodovia ID: {}", rodoviaId);
        return kmRodoviaRepository.findByRodoviaId(rodoviaId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────

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
        Rodovia salva = rodoviaRepository.save(rodovia);
        rodoviaCache.put(salva.getNome(), salva);
        log.info("[Domínio] Rodovia cadastrada manualmente: {}", salva.getNome());
        return salva;
    }

    @Transactional
    @CacheEvict(value = "lista-rodovias", allEntries = true)
    public void deletarRodovia(Long id) {
        rodoviaRepository.deleteById(id);
        rodoviaCache.values().removeIf(r -> r.getId().equals(id));
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


    // ─────────────────────────────────────────────────────────────
    // APRENDIZADO EM LOTE
    // ─────────────────────────────────────────────────────────────
    /**
     * Registra rodovias e KMs descobertos durante o processamento de arquivos.
     * Usa saveAll para minimizar round-trips ao banco.
     *
     * @param descobertas mapa {@code nomeRodovia → Set<km>}
     *                    (KMs podem estar vazios para registros RECEBIDOS)
     */
    @Transactional
    //@CacheEvict(value = {"lista-rodovias", "lista-kms"}, allEntries = true)
    public boolean registrarDescobertas(Map<String, Set<String>> descobertas) {
        if (descobertas == null || descobertas.isEmpty()) return false;

        log.info("🧠 [Domínio] Aprendizado: {} rodovia(s) descoberta(s).", descobertas.size());

        boolean novosRegistros = false;

        // Garante cache atualizado
        if (rodoviaCache.isEmpty()) {
            rodoviaRepository.findAll().forEach(r -> rodoviaCache.put(r.getNome(), r));
        }

        List<KmRodovia> novosKms = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : descobertas.entrySet()) {
            String nomeRodovia = entry.getKey();
            Set<String> kms = entry.getValue();

            // Evita fazer save() dentro de blocos lambdas (computeIfAbsent) para não travar threads
            Rodovia rodovia = rodoviaCache.get(nomeRodovia);
            if (rodovia == null) {
                log.info("[Domínio] Nova rodovia descoberta pelo SFTP: {}", nomeRodovia);
                rodovia = rodoviaRepository.save(Rodovia.builder().nome(nomeRodovia).build());
                rodoviaCache.put(nomeRodovia, rodovia);
                novosRegistros = true;
            }

            if (kms.isEmpty()) continue;

            // Uso de ConcurrentHashMap.newKeySet() elimina a necessidade de blocos 'synchronized'
            Set<String> kmsExistentes = kmCachePorRodovia.computeIfAbsent(rodovia.getId(), id ->
                    kmRodoviaRepository.findByRodoviaId(id).stream()
                            .map(KmRodovia::getValor)
                            .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet))
            );

            for (String valorKm : kms) {
                if (kmsExistentes.add(valorKm)) {
                    novosKms.add(KmRodovia.builder().valor(valorKm).rodovia(rodovia).build());
                    novosRegistros = true;
                }
            }
        }

        if (!novosKms.isEmpty()) {
            kmRodoviaRepository.saveAll(novosKms);
            log.info("[Domínio] {} novo(s) KM(s) cadastrado(s) automaticamente.", novosKms.size());
        }

        // Limpa o Redis APENAS se realmente descobrimos algo novo
        if (novosRegistros) {
            limparCachesDeDominioProgramaticamente();
        }

        return novosRegistros;
    }

    private void limparCachesDeDominioProgramaticamente() {
        Optional.ofNullable(cacheManager.getCache("lista-rodovias")).ifPresent(Cache::clear);
        Optional.ofNullable(cacheManager.getCache("lista-kms")).ifPresent(Cache::clear);
        log.info("[Domínio] Caches do Redis invalidados devido a novas descobertas.");
    }

    // ─────────────────────────────────────────────────────────────
    // AUXILIAR
    // ─────────────────────────────────────────────────────────────

    private KmRodoviaDTO toDTO(KmRodovia entity) {
        return new KmRodoviaDTO(entity.getId(), entity.getValor(), entity.getRodovia().getId());
    }
}
