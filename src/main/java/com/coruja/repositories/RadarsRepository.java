package com.coruja.repositories;

import com.coruja.entities.Radars;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Repository
public interface RadarsRepository  extends JpaRepository<Radars, Long>, JpaSpecificationExecutor<Radars> {

    // Spring Data JPA entende esse nome de método e cria a query:
    // SELECT * FROM radars WHERE placa = ?
    Page<Radars> findByPlaca(String placa, Pageable pageable);

    @Query("SELECT DISTINCT r.rodovia FROM Radars r WHERE r.rodovia IS NOT NULL AND r.rodovia != '' ORDER BY r.rodovia")
    List<String> findDistinctRodovias();

    @Query("SELECT DISTINCT r.km FROM Radars r WHERE r.km IS NOT NULL AND r.km != '' ORDER BY r.km")
    List<String> findDistinctKms();

    @Query("SELECT DISTINCT r.sentido FROM Radars r WHERE r.sentido IS NOT NULL AND r.sentido != '' ORDER BY r.sentido")
    List<String> findDisntictSentidos();

    @Query("SELECT DISTINCT r.praca FROM Radars r WHERE r.praca IS NOT NULL AND r.praca != '' ORDER BY r.praca")
    List<String> findDistinctPracas();

    @Query("SELECT DISTINCT r.km FROM Radars r WHERE r.rodovia = :rodovia AND r.km IS NOT NULL AND r.km <> '' ORDER BY r.km")
    List<String> findDistinctKmsByRodovia(@Param("rodovia") String rodovia);


    /**
     * MÉTODO PARA BUSCA POR PROXIMIDADE
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
    @Query(value = "SELECT * FROM radars_cart " + // IMPORTANTE: Mude 'radars_cart' para a tabela correta em cada serviço
            "WHERE data = :data " +
            "AND hora BETWEEN :horaInicial AND :horaFinal " +
            "AND ST_DWithin(localizacao, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326), :raioEmMetros)",
            nativeQuery = true)
    Page<Radars> findByProximity(
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("raioEmMetros") double raioEmMetros,
            @Param("data") LocalDate data,
            @Param("horaInicial") LocalTime horaInicial,
            @Param("horaFinal") LocalTime horaFinal,
            Pageable pageable
    );


}
