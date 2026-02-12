package com.coruja.services;

import com.coruja.dto.KmRodoviaDTO;
import com.coruja.entities.KmRodovia;
import com.coruja.entities.Rodovia;
import com.coruja.repositories.KmRodoviaRepository;
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
    private final KmRodoviaRepository kmRepository;

    // ✅ Cache Thread-safe de nível de classe para evitar batida no banco e race conditions
    private final ConcurrentHashMap<String, Rodovia> rodoviaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> kmCachePorRodovia = new ConcurrentHashMap<>();

    //@Cacheable(value = "lista-rodovias")
    public List<Rodovia> listarRodovias() {
        // Se o cache estiver vazio, tenta carregar do banco
        if (rodoviaCache.isEmpty()) {
            log.info("🚚 Cache de rodovias vazio, carregando do banco de dados...");
            List<Rodovia> doBanco = rodoviaRepository.findAll();
            doBanco.forEach(r -> rodoviaCache.putIfAbsent(r.getNome(), r));
        }

        // Retorna a lista a partir dos valores do cache
        return new ArrayList<>(rodoviaCache.values());
    }

    @Transactional
    @CacheEvict(value = "lista-rodovias", allEntries = true)
    public Rodovia salvarRodovia(Rodovia rodovia) {
        // Verifica existência para evitar duplicidade
        if (rodoviaRepository.existsByNome(rodovia.getNome())) {
            throw new IllegalArgumentException("Rodovia já existe.");
        }
        Rodovia salva = rodoviaRepository.save(rodovia);
        rodoviaCache.put(salva.getNome(), salva); // Atualiza cache imediatamente
        return salva;
    }

    @Transactional
    public void deletarRodovia(Long id) {
        rodoviaRepository.findById(id).ifPresent(r -> rodoviaCache.remove(r.getNome()));
        kmCachePorRodovia.remove(id);
        rodoviaRepository.deleteById(id);
    }

    // --- KMs ---
    @Cacheable(value = "lista-kms", key = "#rodoviaId")
    public List<KmRodoviaDTO> listarKmsPorRodovia(Long rodoviaId) {
        List<KmRodovia> kms = kmRepository.findByRodoviaId(rodoviaId);

        // Converte para DTO antes de cachear/retornar
        return kms.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = "lista-kms", key = "#km.rodovia.id")
    public KmRodovia salvarKm(KmRodovia km) {
        return kmRepository.save(km);
    }

    @Transactional
    @CacheEvict(value = "lista-kms", allEntries = true)
    public void deletarKm(Long id){
        kmRepository.deleteById(id);
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
     * ✅ MÉTODO NOVO: APRENDIZADO EM LOTE
     * Recebe um Mapa: Chave = Nome da Rodovia, Valor = Lista de KMs encontrados
     */
    @Transactional
    @CacheEvict(value = {"lista-rodovias", "lista-kms"}, allEntries = true)
    public void registrarDescobertas(Map<String, Set<String>> descobertas) {
        if (descobertas.isEmpty()) return;

        log.info("🧠 Aprendizado de domínio: Processando {} rodovias...", descobertas.size());

        // 1. Inicialização preguiçosa (Lazy Load) do cache se estiver vazio
        if (rodoviaCache.isEmpty()) {
            rodoviaRepository.findAll().forEach(r -> rodoviaCache.put(r.getNome(), r));
        }

        List<KmRodovia> novosKmsParaSalvar = new ArrayList<>();

        descobertas.forEach((nomeRodovia, listaKms) -> {
            // ✅ Uso de computeIfAbsent para garantir que apenas UMA thread crie a rodovia
            Rodovia rodovia = rodoviaCache.computeIfAbsent(nomeRodovia, nome -> {
                log.info("🆕 Registrando nova Rodovia no domínio: {}", nome);
                return rodoviaRepository.save(Rodovia.builder().nome(nome).build());
            });

            // 2. Tratamento de KMs com cache local por rodovia
            Set<String> kmsExistentes = kmCachePorRodovia.computeIfAbsent(rodovia.getId(), id -> {
                // Se não está no cache, busca do banco ou inicializa
                return kmRepository.findByRodoviaId(id).stream()
                        .map(KmRodovia::getValor)
                        .collect(Collectors.toCollection(HashSet::new));
            });

            for (String valorKm : listaKms) {
                // ✅ Sincronização fina no set de KMs para evitar duplicatas em novosKmsParaSalvar
                synchronized (kmsExistentes) {
                    if (kmsExistentes.add(valorKm)) {
                        novosKmsParaSalvar.add(KmRodovia.builder()
                                .valor(valorKm)
                                .rodovia(rodovia)
                                .build());
                    }
                }
            }
        });

        // 3. Persistência em lote (Batch) para performance
        if (!novosKmsParaSalvar.isEmpty()) {
            kmRepository.saveAll(novosKmsParaSalvar);
            log.info("💾 Sucesso: {} novos KMs adicionados ao domínio - Concessionária Eixo.", novosKmsParaSalvar.size());
        }
    }
}
