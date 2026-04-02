package com.coruja.repositories;

import com.coruja.entities.ArquivoSftpProcessado;
import com.coruja.enuns.StatusProcessamento;
import com.coruja.enuns.TipoFonte;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface ArquivoSftpProcessadoRepository extends JpaRepository<ArquivoSftpProcessado, Long> {

    /** Verifica se um arquivo já foi registrado (independente de status). */
    boolean existsByNomeArquivo(String nomeArquivo);

    Optional<ArquivoSftpProcessado> findByNomeArquivo(String nomeArquivo);

    /** Retorna nomes de todos os arquivos já conhecidos de um tipo de fonte. */
    @Query("SELECT a.nomeArquivo FROM ArquivoSftpProcessado a WHERE a.tipoFonte = :tipoFonte")
    Set<String> findNomesByTipoFonte(@Param("tipoFonte") TipoFonte tipoFonte);

    /** Lista arquivos com erro que ainda podem ser retentados. */
    List<ArquivoSftpProcessado> findByStatusAndTentativasLessThan(
            StatusProcessamento status, int maxTentativas);

    /**
     * Marca arquivos como PROCESSADO em lote — evita N updates individuais.
     *
     * <p>{@code @Modifying} exige uma transação ativa. O {@code @Transactional}
     * aqui garante que o Spring abre uma transação própria caso o chamador
     * não possua uma — como ocorre dentro de lambdas em {@code ifPresentOrElse}.
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE ArquivoSftpProcessado a
        SET a.status = :status,
            a.processadoEm = :agora,
            a.registrosSalvos = :registros
        WHERE a.id = :id
        """)
    void atualizarStatus(
            @Param("id") Long id,
            @Param("status") StatusProcessamento status,
            @Param("agora") LocalDateTime agora,
            @Param("registros") int registros
    );
}
