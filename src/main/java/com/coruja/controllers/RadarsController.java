package com.coruja.controllers;

import com.coruja.dto.*;
import com.coruja.entities.KmPraca;
import com.coruja.entities.Radars;
import com.coruja.entities.Praca;
import com.coruja.repositories.RadarsRepository;
import com.coruja.services.GestaoRodoviaService;
import com.coruja.services.RadarsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping(value = "/radares")
@CrossOrigin(origins = "${cors.origins}")
@RequiredArgsConstructor
@Slf4j
public class RadarsController {

    private final RadarsService radarsService;
    private final GestaoRodoviaService gestaoRodoviaService;

    /**
     * ✅ BUSCA POR PLACA
     * Endpoint específico e otimizado para histórico completo de uma placa.
     */
    @GetMapping("/busca-placa")
    public ResponseEntity<Page<RadarsDTO>> buscarPorPlaca(
            @RequestParam String placa,
            @PageableDefault(page = 0, size = 20, sort = "data", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(radarsService.buscarPorPlaca(placa, pageable));
    }

    /**
     * ✅ BUSCA POR LOCAL (FILTROS)
     * Endpoint para consulta operacional (Dia, Rodovia, Km, Hora).
     * 'Data' é obrigatória para performance (cai na partição correta).
     */
    @GetMapping("/busca-local")
    public ResponseEntity<RadarPageDTO> buscarPorLocal(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            @RequestParam(required = false) String praca,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,

            // Paginação Padrão
            @PageableDefault(size = 20, sort = {"data", "hora"}, direction = Sort.Direction.DESC) Pageable pageable
    ) {
        // Log para debug (verifique se o sentido aparece aqui no console)
        log.info("🔍 [Cart Controller] Buscando Local | Data: {} | Praca: {} | Sentido: {}", data, praca, sentido);
        RadarPageDTO resultado = radarsService.buscarPorLocal(
                data,
                horaInicial,
                horaFinal,
                praca,
                km,
                sentido,
                pageable
        );
        return ResponseEntity.ok(resultado);
    }

    /**
     * Endpoint para busca Geoespacial (Latitude/Longitude).
     * Exemplo de chamada:
     * GET /radares/geo-search?lat=-22.89&lon=-48.45&data=2025-12-15&horaInicial=08:00&horaFinal=10:00&raio=500
     */
    public ResponseEntity<Page<RadarsDTO>> buscarPorLocalizacao(
            @RequestParam("latitude") Double latitude,
            @RequestParam("longitude") Double longitude,
            @RequestParam(value = "raio", required = false, defaultValue = "15000") Double raio,

            @RequestParam("data")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,

            @RequestParam("horaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicio,

            @RequestParam("horaFim")
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFim,
            @PageableDefault(page = 0, size = 20) Pageable pageable
    ) {
        Page<RadarsDTO> resultado = radarsService.buscarPorGeolocalizacao(
                latitude, longitude, raio, data, horaInicio, horaFim, pageable
        );
        return ResponseEntity.ok(resultado);
    }

    // ==================================================================================
    // 2. GESTÃO DE DOMÍNIOS (RODOVIAS E KMs) - NOVO
    // ==================================================================================
    @GetMapping("/praca")
    public ResponseEntity<List<Praca>> listarPracas() {
        return ResponseEntity.ok(gestaoRodoviaService.listarPracas());
    }

    @PostMapping("/pracas")
    public ResponseEntity<Praca> adicionarPraca(@RequestBody Praca praca) {
        return ResponseEntity.ok(gestaoRodoviaService.salvarPraca(praca));
    }

    @DeleteMapping("/pracas/{id}")
    public ResponseEntity<Void> removerPraca(@PathVariable Long id) {
        gestaoRodoviaService.deletarPraca(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rodovias/{pracaId}/kms")
    public ResponseEntity<List<KmPracaDTO>> listarKmsDaPraca(@PathVariable Long pracaId) {
        return ResponseEntity.ok(gestaoRodoviaService.listarKmsPorPraca(pracaId));
    }

    @PostMapping("/kms")
    public ResponseEntity<KmPraca> adicionarKm(@RequestBody KmPraca km) {
        return ResponseEntity.ok(gestaoRodoviaService.salvarKm(km));
    }

    @DeleteMapping("/kms/{id}")
    public ResponseEntity<Void> removerKm(@PathVariable Long id) {
        gestaoRodoviaService.deletarKm(id);
        return ResponseEntity.noContent().build();
    }

    // ==================================================================================
    // 3. COMPATIBILIDADE / LEGADO (MAPA)
    // ==================================================================================

    @GetMapping("/all-locations")
    public ResponseEntity<List<LocalizacaoRadarProjection>> getRadarLocations() {
        return ResponseEntity.ok(radarsService.listarTodasLocalizacoes());
    }

}
