package com.coruja.dto;

import com.coruja.enuns.TipoFonte;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Parâmetros de busca por localização.
 *
 * <p>O campo {@code tipoFonte} é obrigatório e determina qual query
 * será executada (RECEBIDOS = sem KM | RADAR = com KM).
 */
@Value
@Builder
public class BuscaLocalRequest {
    LocalDate data;
    LocalTime horaInicial;
    LocalTime horaFinal;
    String rodovia;

    /**
     * Apenas utilizado quando {@code tipoFonte == RADAR}.
     */
    String km;

    String sentido;

    /** Determina o fluxo de busca. */
    TipoFonte tipoFonte;
}
