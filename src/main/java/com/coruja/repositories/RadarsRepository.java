package com.coruja.repositories;

import com.coruja.entities.Radars;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Repository
public interface RadarsRepository  extends JpaRepository<Radars, Long>, JpaSpecificationExecutor<Radars> {

    /**
     * ✅ BUSCA OTIMIZADA POR PLACA
     * Mudado para Native Query para garantir uso do índice GIN (pg_trgm) e evitar erro de mapeamento.
     */
    @Query(value = """
        SELECT DISTINCT ON (r.data, r.hora, r.placa) r.* FROM radars_eixo r
        WHERE r.placa ILIKE CONCAT('%', :placa, '%')
        ORDER BY r.data DESC, r.hora DESC
        """,
            countQuery = """
        SELECT COUNT(DISTINCT (r.data, r.hora, r.placa))
        FROM radars_eixo r
        WHERE r.placa ILIKE CONCAT('%', :placa, '%')
        """, nativeQuery = true)
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    Page<Radars> findAllByPlaca(@Param("placa") String placa, Pageable pageable);

    // 2. BUSCA POR LOCAL (Filtros Específicos: Data, Hora, Rodovia, Km, Sentido)
    // Otimização: Query Nativa para evitar overhead do Hibernate em projeções complexas
    /**
     * ✅ BUSCA COM FILTROS COMBINADOS
     */
    @Query(value = """
    SELECT DISTINCT ON (r.data, r.hora, r.placa) r.* FROM radars_eixo r
    WHERE 1=1
    AND (CAST(:placa AS TEXT) IS NULL OR r.placa ILIKE CONCAT('%', CAST(:placa AS TEXT), '%'))
    AND (CAST(:rodovia AS TEXT) IS NULL OR r.rodovia ILIKE CONCAT('%', CAST(:rodovia AS TEXT), '%'))
    AND (CAST(:km AS TEXT) IS NULL OR r.km = CAST(:km AS TEXT))
    AND (CAST(:sentido AS TEXT) IS NULL OR r.sentido ILIKE CAST(:sentido AS TEXT)) -- Alterado para ILIKE
    AND (CAST(:data AS DATE) IS NULL OR r.data = CAST(:data AS DATE))
    AND (CAST(:horaInicial AS TIME) IS NULL OR r.hora >= CAST(:horaInicial AS TIME))
    AND (CAST(:horaFinal AS TIME) IS NULL OR r.hora <= CAST(:horaFinal AS TIME))
    ORDER BY r.data DESC, r.hora DESC, r.placa
    """,
            nativeQuery = true
    )
    @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
    Page<Radars> findByLocalFilter(
            @Param("data") LocalDate data,
            @Param("horaInicial") LocalTime horaInicial,
            @Param("horaFinal") LocalTime horaFinal,
            @Param("placa") String placa,
            @Param("rodovia") String rodovia,
            @Param("km") String km,
            @Param("sentido") String sentido,
            Pageable pageable
    );

    /**
     * ✅ BUSCA GEOESPACIAL OTIMIZADA
     * Busca passagens de radar que ocorreram dentro de um raio específico
     * a partir de um ponto de coordenadas, em um determinado intervalo de tempo.
     * Utiliza uma consulta SQL nativa para aproveitar as funções do PostGIS.
     *
     * @param latitude A latitude do ponto de busca.
     * @param longitude A longitude do ponto de busca.
     * @param raioEmMetros O raio da busca em metros (ex: 500.0 para 500 metros).
     * @param data A data da pesquisa.
     * @param horaInicial A hora inicial do intervalo de pesquisa.
     * @param horaFinal A hora final do intervalo de pesquisa.
     * @param pageable Objeto de paginação.
     * @return Uma página de registros de radar encontrados.
     * */
    @Query(value = """
        SELECT DISTINCT ON (r.data, r.hora, r.placa) r.* FROM radars_eixo r
        INNER JOIN localizacao_radar l ON r.localizacao_id = l.id
        WHERE r.data = CAST(:data AS DATE)
        AND r.hora BETWEEN CAST(:horaInicio AS TIME) AND CAST(:horaFim AS TIME)
        AND ST_DWithin(
            l.localizacao,
            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
            :raio
        )
        ORDER BY r.data DESC, r.hora DESC, r.placa
        """,
            countQuery = """
        SELECT COUNT(DISTINCT (r.data, r.hora, r.placa))
        FROM radars_eixo r
        INNER JOIN localizacao_radar l ON r.localizacao_id = l.id
        WHERE r.data = CAST(:data AS DATE)
        AND r.hora BETWEEN CAST(:horaInicio AS TIME) AND CAST(:horaFim AS TIME)
        AND ST_DWithin(
            l.localizacao,
            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
            :raio
        )
        """,
            nativeQuery = true
    )
    Page<Radars> findByLocalizacaoFilter(
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("raioEmMetros") double raioEmMetros,
            @Param("data") LocalDate data,
            @Param("horaInicial") LocalTime horaInicial,
            @Param("horaFinal") LocalTime horaFinal,
            Pageable pageable
    );

    /**
     * ✅ METADATA DE FILTROS
     */
    @Query(value = """
        WITH dados_recentes AS (
            SELECT rodovia, rodovia, km, sentido
            FROM radars_eixo
            WHERE data >= CURRENT_DATE - INTERVAL '30 days'
        )
        SELECT DISTINCT rodovia FROM dados_recentes WHERE rodovia IS NOT NULL ORDER BY rodovia
        """, nativeQuery = true)
    List<String> findDistinctRodoviasOtimizado();

    @Query(value = """
        SELECT DISTINCT rodovia FROM radars_eixo
        WHERE data >= CURRENT_DATE - INTERVAL '30 days'
        AND rodovia IS NOT NULL
        ORDER BY rodovia
        """, nativeQuery = true)
    List<String> findDistinctPracasOtimizado();

    @Query(value = """
        SELECT DISTINCT km FROM radars_eixo
        WHERE rodovia = :rodovia
        AND data >= CURRENT_DATE - INTERVAL '30 days'
        AND km IS NOT NULL
        ORDER BY CAST(REGEXP_REPLACE(km, '[^0-9.]', '', 'g') AS NUMERIC)
        """, nativeQuery = true)
    List<String> findDistinctKmsByRodoviaOtimizado(@Param("rodovia") String rodovia);

}
