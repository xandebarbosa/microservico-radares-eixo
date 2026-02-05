package com.coruja.repositories;

import com.coruja.dto.LocalizacaoRadarProjection;
import com.coruja.entities.LocalizacaoRadar;
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
import java.util.Optional;

@Repository
public interface LocalizacaoRadarRepository extends JpaRepository<LocalizacaoRadar, Long> {

    // Busca uma localização pela combinação de rodovia e km
    Optional<LocalizacaoRadar> findByRodoviaAndKm(String rodovia, String km);

    @Query(value = """
            SELECT
            id,
            concessionaria,
            rodovia,
            km,
            praca,
            ST_Y(localizacao::geometry) as latitude,
            ST_X(localizacao::geometry) as longitude
            FROM localizacao_radar
            """, nativeQuery = true)
    List<LocalizacaoRadarProjection> findAllLocations();
}
