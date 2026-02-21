package com.coruja.controllers;

import com.coruja.dto.*;
import com.coruja.entities.KmRodovia;
import com.coruja.entities.Rodovia;
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
    public ResponseEntity<Page<RadarsDTO>> buscarPorPlaca(
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
        log.info("🌍 [Cart] Busca geoespacial | Lat: {} | Long: {} | Raio: {}m", latitude, longitude, raio);
        Page<RadarsDTO> resultado = radarsService.buscarPorGeolocalizacao(
                latitude, longitude, raio, data, horaInicio, horaFim, pageable
        );
        return ResponseEntity.ok(resultado);
    }

    // ==================================================================================
    // 2. GESTÃO DE DOMÍNIOS (RODOVIAS E KMs)
    // ==================================================================================
    @GetMapping("/rodovias")
    public ResponseEntity<List<RodoviaDTO>> listarPracas() {
        log.info("🛣️ [Eixo] Listando rodovias");

        List<Rodovia> rodovias = gestaoRodoviaService.listarRodovias();

        //Converte entidades para DTOs
        List<RodoviaDTO> rodoviaDTOS = rodovias.stream()
                .map(this::convertToRodoviasDTO)
                .collect(Collectors.toList());

        log.info("✅ [Eixo] Retornando {} rodovias", rodoviaDTOS.size());

        return ResponseEntity.ok(rodoviaDTOS);
    }

    /**
     * ✅ Adiciona nova rodovia
     */
    @PostMapping("/rodovias")
    public ResponseEntity<RodoviaDTO> adicionarRodovia(@RequestBody RodoviaDTO rodoviaDTO) {
        log.info("➕ [Cart] Adicionando rodovia: {}", rodoviaDTO.getNome());

        // Converte DTO para entidade
        Rodovia rodovia = new Rodovia();
        rodovia.setNome(rodoviaDTO.getNome());

        // Salva
        Rodovia savedRodovia = gestaoRodoviaService.salvarRodovia(rodovia);

        // Retorna DTO
        return ResponseEntity.ok(convertToRodoviasDTO(savedRodovia));

    }

    /**
     * ✅ Remove rodovia
     */
    @DeleteMapping("/rodovias/{id}")
    public ResponseEntity<Void> removerRodovia(@PathVariable Long id) {
        log.info("🗑️ [Eixo] Removendo rodovia ID: {}", id);
        gestaoRodoviaService.deletarRodovia(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * ✅ Lista KMs de uma rodovia específica
     * Já retorna DTO (método do service já faz isso)
     */
    @GetMapping("/rodovias/{rodoviaId}/kms")
    public ResponseEntity<List<KmRodoviaDTO>> listarKmsDaRodovia(@PathVariable Long rodoviaId) {
        log.info("📍 [Eixo] Listando KMs da rodovia ID: {}", rodoviaId);

        List<KmRodoviaDTO> kms = gestaoRodoviaService.listarKmsPorRodovia(rodoviaId);

        log.info("✅ [Eixo] Retornando {} KMs", kms.size());

        return ResponseEntity.ok(kms);

    }

    /**
     * ✅ Adiciona novo KM
     */
    @PostMapping("/kms")
    public ResponseEntity<KmRodoviaDTO> adicionarKm(@RequestBody KmRodoviaDTO kmRodoviaDTO) {
        log.info("➕ [Eixo] Adicionando KM: {} para rodovia ID: {}", kmRodoviaDTO.getValor(), kmRodoviaDTO.getRodoviaId());

        // Cria entidade a partir do DTO
        KmRodovia km = new KmRodovia();
        km.setValor(kmRodoviaDTO.getValor());

        // Precisa buscar a rodovia pelo ID
        Rodovia rodovia = new Rodovia();
        rodovia.setId(kmRodoviaDTO.getRodoviaId());
        km.setRodovia(rodovia);

        // Salva
        KmRodovia savedKm = gestaoRodoviaService.salvarKm(km);

        //Retorna DTO
        return ResponseEntity.ok(convertToKmDTO(savedKm));
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
    private RodoviaDTO convertToRodoviasDTO(Rodovia rodovia) {
        return RodoviaDTO.builder()
                .id(rodovia.getId())
                .nome(rodovia.getNome())
                .build();
    }

    /**
     * Converte entidade KmRodovia para DTO
     */
    private KmRodoviaDTO convertToKmDTO(KmRodovia km) {
        return KmRodoviaDTO.builder()
                .id(km.getId())
                .valor(km.getValor())
                .rodoviaId(km.getRodovia().getId())
                .build();
    }

}
