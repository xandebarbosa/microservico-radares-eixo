package com.coruja.dto;


import com.coruja.enuns.TipoFonte;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FilterOptionsDTO implements Serializable {
    // Implementar Serializable é boa pratica para cache Redis
    @Serial
    private static final long serialVersionUID = 1L;

    /** Fonte dos filtros — define se os KMs estarão presentes. */
    private TipoFonte tipoFonte;

    private List<String> rodovias;

    /** Presente apenas quando {@code tipoFonte == RADAR}. */
    private List<String> kms;
    private List<String> sentidos;
}
