package com.coruja.services;

import com.coruja.dto.KmPracaDTO;
import com.coruja.entities.KmPraca;
import com.coruja.entities.Praca;
import com.coruja.entities.Rodovia;
import com.coruja.repositories.KmPracaRepository;
import com.coruja.repositories.PracaRepository;
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
    private final PracaRepository pracaRepository;
    private final KmPracaRepository kmRepository;

    // ✅ Cache Thread-safe de nível de classe para evitar batida no banco e race conditions
    private final ConcurrentHashMap<String, Praca> pracaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> kmCachePorPraca = new ConcurrentHashMap<>();

    //@Cacheable(value = "lista-pracas")
    public List<Praca> listarPracas() {
        // Se o cache estiver vazio, tenta carregar do banco
        if (pracaCache.isEmpty()) {
            log.info("🚚 Cache de pracas vazio, carregando do banco de dados...");
            List<Praca> doBanco = pracaRepository.findAll();
            doBanco.forEach(r -> pracaCache.putIfAbsent(r.getNome(), r));
        }

        // Retorna a lista a partir dos valores do cache
        return new ArrayList<>(pracaCache.values());
    }

    @Transactional
    @CacheEvict(value = "lista-pracas", allEntries = true)
    public Praca salvarPraca(Praca praca) {
        // Verifica existência para evitar duplicidade
        if (pracaRepository.existsByNome(praca.getNome())) {
            throw new IllegalArgumentException("Praca já existe.");
        }
        Praca salva = pracaRepository.save(praca);
        pracaCache.put(salva.getNome(), salva); // Atualiza cache imediatamente
        return salva;
    }

    @Transactional
    public void deletarPraca(Long id) {
        pracaRepository.findById(id).ifPresent(r -> pracaCache.remove(r.getNome()));
        kmCachePorPraca.remove(id);
        pracaRepository.deleteById(id);
    }

    // --- KMs ---
    @Cacheable(value = "lista-kms", key = "#pracaId")
    public List<KmPracaDTO> listarKmsPorPraca(Long pracaId) {
        List<KmPraca> kms = kmRepository.findByPracaId(pracaId);

        // Converte para DTO antes de cachear/retornar
        return kms.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = "lista-kms", key = "#km.praca.id")
    public KmPraca salvarKm(KmPraca km) {
        return kmRepository.save(km);
    }

    @Transactional
    @CacheEvict(value = "lista-kms", allEntries = true)
    public void deletarKm(Long id){
        kmRepository.deleteById(id);
    }

    // Método auxiliar de conversão
    private KmPracaDTO toDTO(KmPraca entity) {
        return new KmPracaDTO(
                entity.getId(),
                entity.getValor(),
                entity.getPraca().getId()
        );
    }

    /**
     * ✅ MÉTODO NOVO: APRENDIZADO EM LOTE
     * Recebe um Mapa: Chave = Nome da Praca, Valor = Lista de KMs encontrados
     */
    @Transactional
    @CacheEvict(value = {"lista-pracas", "lista-kms"}, allEntries = true)
    public void registrarDescobertas(Map<String, Set<String>> descobertas) {
        if (descobertas.isEmpty()) return;

        log.info("🧠 Aprendizado de domínio: Processando {} pracas...", descobertas.size());

        // 1. Inicialização preguiçosa (Lazy Load) do cache se estiver vazio
        if (pracaCache.isEmpty()) {
            pracaRepository.findAll().forEach(r -> pracaCache.put(r.getNome(), r));
        }

        List<KmPraca> novosKmsParaSalvar = new ArrayList<>();

        descobertas.forEach((nomePraca, listaKms) -> {
            // ✅ Uso de computeIfAbsent para garantir que apenas UMA thread crie a praca
            Praca praca = pracaCache.computeIfAbsent(nomePraca, nome -> {
                log.info("🆕 Registrando nova Praca no domínio: {}", nome);
                return pracaRepository.save(Praca.builder().nome(nome).build());
            });

            // 2. Tratamento de KMs com cache local por praca
            Set<String> kmsExistentes = kmCachePorPraca.computeIfAbsent(praca.getId(), id -> {
                // Se não está no cache, busca do banco ou inicializa
                return kmRepository.findByPracaId(id).stream()
                        .map(KmPraca::getValor)
                        .collect(Collectors.toCollection(HashSet::new));
            });

            for (String valorKm : listaKms) {
                // ✅ Sincronização fina no set de KMs para evitar duplicatas em novosKmsParaSalvar
                synchronized (kmsExistentes) {
                    if (kmsExistentes.add(valorKm)) {
                        novosKmsParaSalvar.add(KmPraca.builder()
                                .valor(valorKm)
                                .praca(praca)
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
