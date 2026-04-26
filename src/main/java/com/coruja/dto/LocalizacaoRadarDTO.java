package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalizacaoRadarDTO implements Serializable {
    private Long id;
    private String concessionaria;
    private String rodovia;
    private String km;
    private Double latitude;
    private Double longitude;
}
