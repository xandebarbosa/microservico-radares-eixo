package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RadarPageDTO implements Serializable {

    //Lista de radares (DTOs já convertidos)
    private List<RadarsDTO> content;

    //Informaçoes de paginação
    private PageMetadata page;
}
