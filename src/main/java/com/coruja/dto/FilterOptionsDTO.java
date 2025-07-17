package com.coruja.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class FilterOptionsDTO {
    private List<String> rodovias;
    private List<String> kms;
    private List<String> sentidos;
    private List<String> pracas;

    // 2. Construtor Vazio (equivalente ao @NoArgsConstructor)
    // Obrigatório para muitas bibliotecas como Jackson (JSON) e JPA.
    public FilterOptionsDTO() {
    }

    // 3. Construtor com Todos os Argumentos (equivalente ao @AllArgsConstructor)
    // Este é o construtor que seu RadarsService precisa para criar o objeto.
    public FilterOptionsDTO(List<String> rodovias, List<String> kms, List<String> sentidos, List<String> pracas) {
        this.rodovias = rodovias;
        this.kms = kms;
        this.sentidos = sentidos;
        this.pracas = pracas;
    }

    public List<String> getRodovias() {
        return rodovias;
    }

    public void setRodovias(List<String> rodovias) {
        this.rodovias = rodovias;
    }

    public List<String> getKms() {
        return kms;
    }

    public void setKms(List<String> kms) {
        this.kms = kms;
    }

    public List<String> getSentidos() {
        return sentidos;
    }

    public void setSentidos(List<String> sentidos) {
        this.sentidos = sentidos;
    }

    public List<String> getPracas() {
        return pracas;
    }

    public void setPracas(List<String> pracas) {
        this.pracas = pracas;
    }
}
