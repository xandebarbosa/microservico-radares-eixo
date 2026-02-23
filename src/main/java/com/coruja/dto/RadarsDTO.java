package com.coruja.dto;

import com.coruja.enuns.Sentido;
import com.coruja.enuns.TipoFonte;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RadarsDTO {
    private Long id;
    private LocalDate data;
    private LocalTime hora;
    private String placa;
    private String rodovia;
    private String km;
    private Sentido sentido;

    /**
     * Origem do dado: RECEBIDOS (sem KM) ou RADAR (com KM).
     * Permite que o front-end diferencie as fontes.
     */
    private TipoFonte tipoFonte;
}
