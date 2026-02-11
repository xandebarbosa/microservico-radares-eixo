package com.coruja.controllers;

import com.coruja.dto.*;
import com.coruja.entities.KmPraca;
import com.coruja.entities.Praca;
import com.coruja.services.GestaoRodoviaService;
import com.coruja.services.RadarsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

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
    public ResponseEntity<Page<RadarDTO>> buscarPorPlaca(
            @RequestParam String placa,
            @PageableDefault(page = 0, size = 20, sort = "data", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        log.info("📍 [Eixo] Buscando por placa: {}", placa);
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
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,

            // Paginação Padrão
            @PageableDefault(size = 20, sort = {"data", "hora"}, direction = Sort.Direction.DESC) Pageable pageable
    ) {
        // Log para debug (verifique se o sentido aparece aqui no console)
        log.info("🔍 [Eixo Controller] Buscando Local | Data: {} | Rodovia: {} | Sentido: {}", data, rodovia, sentido);
        RadarPageDTO resultado = radarsService.buscarPorLocal(
                data,
                horaInicial,
                horaFinal,
                rodovia,
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
    public ResponseEntity<Page<RadarDTO>> buscarPorLocalizacao(
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
        log.info("🌍 [Cart] Busca geoespacial | Lat: {} | Long: {} | Raio: {}m", latitude, longitude, raio);
        Page<RadarDTO> resultado = radarsService.buscarPorGeolocalizacao(
                latitude, longitude, raio, data, horaInicio, horaFim, pageable
        );
        return ResponseEntity.ok(resultado);
    }

    // ==================================================================================
    // 2. GESTÃO DE DOMÍNIOS (RODOVIAS E KMs)
    // ==================================================================================
    @GetMapping("/rodovias")
    public ResponseEntity<List<PracaDTO>> listarPracas() {
        log.info("🛣️ [Eixo] Listando praças");

        List<Praca> pracas = gestaoRodoviaService.listarPracas();

        //Converte entidades para DTOs
        List<PracaDTO> pracaDTOS = pracas.stream()
                .map(this::convertToPracasDTO)
                .collect(Collectors.toList());

        log.info("✅ [Eixo] Retornando {} rodovias", pracaDTOS.size());

        return ResponseEntity.ok(pracaDTOS);
    }

    /**
     * ✅ Adiciona nova rodovia
     */
    @PostMapping("/rodovias")
    public ResponseEntity<PracaDTO> adicionarPraca(@RequestBody PracaDTO pracaDTO) {
        log.info("➕ [Cart] Adicionando rodovia: {}", pracaDTO.getNome());

        // Converte DTO para entidade
        Praca praca = new Praca();
        praca.setNome(pracaDTO.getNome());

        // Salva
        Praca savedPraca = gestaoRodoviaService.salvarPraca(praca);

        // Retorna DTO
        return ResponseEntity.ok(convertToPracasDTO(savedPraca));

    }

    /**
     * ✅ Remove rodovia
     */
    @DeleteMapping("/rodovias/{id}")
    public ResponseEntity<Void> removerPraca(@PathVariable Long id) {
        log.info("🗑️ [Eixo] Removendo rodovia ID: {}", id);
        gestaoRodoviaService.deletarPraca(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * ✅ Lista KMs de uma rodovia específica
     * Já retorna DTO (método do service já faz isso)
     */
    @GetMapping("/rodovias/{pracaId}/kms")
    public ResponseEntity<List<KmPracaDTO>> listarKmsDaPraca(@PathVariable Long pracaId) {
        log.info("📍 [Eixo] Listando KMs da rodovia ID: {}", pracaId);

        List<KmPracaDTO> kms = gestaoRodoviaService.listarKmsPorPraca(pracaId);
        log.info("✅ [Eixo] Retornando {} KMs", kms.size());
        return ResponseEntity.ok(kms);
    }

    /**
     * ✅ Adiciona novo KM
     */
    @PostMapping("/kms")
    public ResponseEntity<KmPracaDTO> adicionarKm(@RequestBody KmPracaDTO kmPracaDTO) {
        log.info("➕ [Cart] Adicionando KM: {} para rodovia ID: {}", kmPracaDTO.getValor(), kmPracaDTO.getPracaId());

        // Cria entidade a partir do DTO
        KmPraca km = new KmPraca();
        km.setValor(kmPracaDTO.getValor());

        // Precisa buscar a praca pelo ID
        Praca praca = new Praca();
        praca.setId(kmPracaDTO.getPracaId());
        km.setPraca(praca);

        // Salva
        KmPraca savedKm = gestaoRodoviaService.salvarKm(km);

        // Retorna DTO
        return ResponseEntity.ok(convertToKmDTO(savedKm));
    }

    /**
     * ✅ Remove KM
     */
    @DeleteMapping("/kms/{id}")
    public ResponseEntity<Void> removerKm(@PathVariable Long id) {
        log.info("🗑️ [Eixo] Removendo KM ID: {}", id);
        gestaoRodoviaService.deletarKm(id);
        return ResponseEntity.noContent().build();
    }

    // ==================================================================================
    // 3. COMPATIBILIDADE / LEGADO (MAPA)
    // ==================================================================================

    @GetMapping("/all-locations")
    public ResponseEntity<List<LocalizacaoRadarProjection>> getRadarLocations() {
        log.info("🗺️ [Eixo] Buscando todas as localizações");
        List<LocalizacaoRadarProjection> locations = radarsService.listarTodasLocalizacoes();
        log.info("✅ [Eixo] Retornando {} localizações", locations.size());
        return ResponseEntity.ok(locations);
    }

    /**
     * Converte entidade Rodovia para DTO
     */
    private PracaDTO convertToPracasDTO(Praca rodovia) {
        return PracaDTO.builder()
                .id(rodovia.getId())
                .nome(rodovia.getNome())
                .build();
    }

    /**
     * Converte entidade KmPraca para DTO
     */
    private KmPracaDTO convertToKmDTO(KmPraca km) {
        return KmPracaDTO.builder()
                .id(km.getId())
                .valor(km.getValor())
                .pracaId(km.getPraca().getId())
                .build();
    }

}
