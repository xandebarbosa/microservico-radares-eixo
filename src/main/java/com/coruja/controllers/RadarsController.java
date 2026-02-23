package com.coruja.controllers;

import com.coruja.dto.*;
import com.coruja.entities.KmRodovia;
import com.coruja.entities.Rodovia;
import com.coruja.enuns.TipoFonte;
import com.coruja.services.domain.GestaoRodoviaService;
import com.coruja.services.radar.RadarsService;
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
@RequestMapping("/radares")
@CrossOrigin(origins = "${cors.origins}")
@RequiredArgsConstructor
@Slf4j
public class RadarsController {

    private final RadarsService        radarsService;
    private final GestaoRodoviaService gestaoRodoviaService;

    // ─────────────────────────────────────────────────────────────
    // BUSCA POR PLACA
    // ─────────────────────────────────────────────────────────────

    /**
     * Histórico completo de passagens de uma placa (todas as fontes).
     */
    @GetMapping("/busca-placa")
    public ResponseEntity<Page<RadarsDTO>> buscarPorPlaca(
            @RequestParam String placa,
            @PageableDefault(size = 20, sort = "data", direction = Sort.Direction.DESC) Pageable pageable) {

        log.info("[Eixo] busca-placa: {}", placa);
        return ResponseEntity.ok(radarsService.buscarPorPlaca(placa, pageable));
    }

    // ─────────────────────────────────────────────────────────────
    // BUSCA POR LOCAL — /recebidos (apenas rodovia)
    // ─────────────────────────────────────────────────────────────

    /**
     * Consulta para dados vindos da pasta {@code /recebidos}.
     * Não existe filtro por KM neste fluxo.
     */
    @GetMapping("/busca-local/recebidos")
    public ResponseEntity<RadarPageDTO> buscarPorLocalRecebidos(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String sentido,
            @PageableDefault(size = 20, sort = {"data", "hora"}, direction = Sort.Direction.DESC) Pageable pageable) {

        BuscaLocalRequest req = BuscaLocalRequest.builder()
                .data(data)
                .horaInicial(horaInicial)
                .horaFinal(horaFinal)
                .rodovia(rodovia)
                .sentido(sentido)
                .tipoFonte(TipoFonte.RECEBIDOS)
                .build();

        return ResponseEntity.ok(radarsService.buscarPorLocal(req, pageable));
    }

    // ─────────────────────────────────────────────────────────────
    // BUSCA POR LOCAL — /radar (rodovia + KM)
    // ─────────────────────────────────────────────────────────────

    /**
     * Consulta para dados vindos da pasta {@code /radar}.
     * Aceita filtro de KM.
     */
    @GetMapping("/busca-local/radar")
    public ResponseEntity<RadarPageDTO> buscarPorLocalRadar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,
            @PageableDefault(size = 20, sort = {"data", "hora"}, direction = Sort.Direction.DESC) Pageable pageable) {

        BuscaLocalRequest req = BuscaLocalRequest.builder()
                .data(data)
                .horaInicial(horaInicial)
                .horaFinal(horaFinal)
                .rodovia(rodovia)
                .km(km)
                .sentido(sentido)
                .tipoFonte(TipoFonte.RADAR)
                .build();

        return ResponseEntity.ok(radarsService.buscarPorLocal(req, pageable));
    }

    // ─────────────────────────────────────────────────────────────
    // BUSCA GEOESPACIAL
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/geo-search")
    public ResponseEntity<Page<RadarsDTO>> buscarPorLocalizacao(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(required = false, defaultValue = "15000") Double raio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFim,
            @PageableDefault(size = 20) Pageable pageable) {

        log.info("[Eixo] geo-search | lat={} lon={} raio={}m", latitude, longitude, raio);
        return ResponseEntity.ok(
                radarsService.buscarPorGeolocalizacao(latitude, longitude, raio, data, horaInicio, horaFim, pageable)
        );
    }

    // ─────────────────────────────────────────────────────────────
    // MAPA
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/all-locations")
    public ResponseEntity<List<LocalizacaoRadarProjection>> getRadarLocations() {
        return ResponseEntity.ok(radarsService.listarTodasLocalizacoes());
    }

    // ─────────────────────────────────────────────────────────────
    // GESTÃO DE DOMÍNIOS
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/rodovias")
    public ResponseEntity<List<RodoviaDTO>> listarRodovias() {
        return ResponseEntity.ok(
                gestaoRodoviaService.listarRodovias().stream()
                        .map(r -> RodoviaDTO.builder().id(r.getId()).nome(r.getNome()).build())
                        .collect(Collectors.toList())
        );
    }

    @PostMapping("/rodovias")
    public ResponseEntity<RodoviaDTO> adicionarRodovia(@RequestBody RodoviaDTO dto) {
        Rodovia entidade = new Rodovia();
        entidade.setNome(dto.getNome());
        Rodovia salva = gestaoRodoviaService.salvarRodovia(entidade);
        return ResponseEntity.ok(RodoviaDTO.builder().id(salva.getId()).nome(salva.getNome()).build());
    }

    @DeleteMapping("/rodovias/{id}")
    public ResponseEntity<Void> removerRodovia(@PathVariable Long id) {
        gestaoRodoviaService.deletarRodovia(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rodovias/{rodoviaId}/kms")
    public ResponseEntity<List<KmRodoviaDTO>> listarKmsDaRodovia(@PathVariable Long rodoviaId) {
        return ResponseEntity.ok(gestaoRodoviaService.listarKmsPorRodovia(rodoviaId));
    }

    @PostMapping("/kms")
    public ResponseEntity<KmRodoviaDTO> adicionarKm(@RequestBody KmRodoviaDTO dto) {
        KmRodovia km = new KmRodovia();
        km.setValor(dto.getValor());
        Rodovia rodovia = new Rodovia();
        rodovia.setId(dto.getRodoviaId());
        km.setRodovia(rodovia);
        KmRodovia salvo = gestaoRodoviaService.salvarKm(km);
        return ResponseEntity.ok(new KmRodoviaDTO(salvo.getId(), salvo.getValor(), salvo.getRodovia().getId()));
    }
}
