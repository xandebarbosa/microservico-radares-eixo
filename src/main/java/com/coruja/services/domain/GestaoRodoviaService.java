package com.coruja.services.domain;

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
        log.info("[Domínio] Carregando rodovias do banco...");
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
        log.info("[Domínio] Rodovia cadastrada: {}", salva.getNome());
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
    @CacheEvict(value = {"lista-rodovias", "lista-kms"}, allEntries = true)
    public void registrarDescobertas(Map<String, Set<String>> descobertas) {
        if (descobertas == null || descobertas.isEmpty()) return;

        log.info("🧠 [Domínio] Aprendizado: {} rodovia(s) descoberta(s).", descobertas.size());

        // Garante cache atualizado
        if (rodoviaCache.isEmpty()) {
            rodoviaRepository.findAll().forEach(r -> rodoviaCache.put(r.getNome(), r));
        }

        List<KmRodovia> novosKms = new ArrayList<>();

        descobertas.forEach((nomeRodovia, kms) -> {
            Rodovia rodovia = rodoviaCache.computeIfAbsent(nomeRodovia, nome -> {
                log.info("[Domínio] Nova rodovia: {}", nome);
                return rodoviaRepository.save(Rodovia.builder().nome(nome).build());
            });

            if (kms.isEmpty()) return; // RECEBIDOS não tem KM, nada a registrar

            Set<String> kmsExistentes = kmCachePorRodovia.computeIfAbsent(rodovia.getId(), id ->
                    kmRodoviaRepository.findByRodoviaId(id).stream()
                            .map(KmRodovia::getValor)
                            .collect(Collectors.toCollection(HashSet::new))
            );

            for (String valorKm : kms) {
                synchronized (kmsExistentes) {
                    if (kmsExistentes.add(valorKm)) {
                        novosKms.add(KmRodovia.builder().valor(valorKm).rodovia(rodovia).build());
                    }
                }
            }
        });

        if (!novosKms.isEmpty()) {
            kmRodoviaRepository.saveAll(novosKms);
            log.info("[Domínio] {} novo(s) KM(s) cadastrado(s).", novosKms.size());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AUXILIAR
    // ─────────────────────────────────────────────────────────────

    private KmRodoviaDTO toDTO(KmRodovia entity) {
        return new KmRodoviaDTO(entity.getId(), entity.getValor(), entity.getRodovia().getId());
    }
}
