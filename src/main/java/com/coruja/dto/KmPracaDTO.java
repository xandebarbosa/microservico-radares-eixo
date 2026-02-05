package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KmPracaDTO implements Serializable {
    private Long id;
    private String valor;
    private Long pracaId; // Trazendo apenas o ID, evitando o loop/erro do Hibertate
}
