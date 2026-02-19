package com.coruja.services;

import com.coruja.entities.Rodovia;
import com.coruja.repositories.RodoviaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class GestaoRodoviaService {

    private final RodoviaRepository rodoviaRepository;

    /**
     * Lista todas as rodovias cadastradas.
     * Utiliza o Redis (cache 'lista-rodovias') para evitar ir ao banco repetidamente.
     */
    @Cacheable(value = "lista-rodovias")
    public List<Rodovia> listarRodovias() {
        log.info("🔍 Buscando rodovias no banco de dados...");
        return rodoviaRepository.findAll();
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
        log.info("💾 Salvando nova rodovia: {}", rodovia.getNome());
        return rodoviaRepository.save(rodovia);
    }

    @Transactional
    @CacheEvict(value = "lista-rodovias", allEntries = true)
    public void deletarRodovia(Long id) {
        rodoviaRepository.deleteById(id);
    }

    /**
     * ✅ MÉTODO REFATORADO: APRENDIZADO EM LOTE OTIMIZADO
     * 1. Recebe apenas a lista de nomes (List<String>), corrigindo o erro de tipo.
     * 2. Busca todas as rodovias existentes de uma vez.
     * 3. Filtra apenas as que são realmente novas.
     * 4. Salva em lote (saveAll), reduzindo drasticamente o tempo de conexão com o banco.
     */
    @Transactional
    @CacheEvict(value = "lista-rodovias", allEntries = true)
    public void registrarDescobertas(List<String> nomesBrutos) {
        if (nomesBrutos == null || nomesBrutos.isEmpty()) return;

        // Remove duplicatas da lista recebida (ex: 50 registros da mesma rodovia no arquivo)
        Set<String> nomesUnicos = new HashSet<>(nomesBrutos);

        log.info("🧠 Aprendizado de domínio: Analisando {} nomes únicos...", nomesUnicos.size());

        // Busca todas as rodovias que JÁ existem no banco para comparar na memória
        // (Isso é muito mais rápido do que fazer um 'existsByNome' para cada item)
        Set<String> rodoviasExistentes = rodoviaRepository.findAll().stream()
                .map(Rodovia::getNome)
                .collect(Collectors.toSet());

        // Filtra: Quero apenas o que NÃO está no banco
        List<Rodovia> novasRodovias = nomesUnicos.stream()
                .filter(nome -> !rodoviasExistentes.contains(nome))
                .map(nome -> Rodovia.builder().nome(nome).build())
                .collect(Collectors.toList());

        if (!novasRodovias.isEmpty()) {
            rodoviaRepository.saveAll(novasRodovias);
            log.info("✅ {} novas rodovias cadastradas no domínio.", novasRodovias.size());
            novasRodovias.forEach(r -> log.debug("   > Nova: {}", r.getNome()));
        } else {
            log.info("🏁 Nenhuma nova rodovia encontrada. O domínio já está atualizado.");
        }
    }
}
