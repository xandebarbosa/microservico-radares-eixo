package com.coruja.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

public class RadarsDTO {
    private Long id;
    private LocalDate data;
    private LocalTime hora;
    private String placa;
    private String praca;
    private String rodovia;
    private String km;
    private String sentido;

    // Construtor privado para evitar instanciação direta
    private RadarsDTO(Builder builder) {
        this.id = builder.id;
        this.data = builder.data;
        this.hora = builder.hora;
        this.placa = builder.placa;
        this.praca = builder.praca;
        this.rodovia = builder.rodovia;
        this.km = builder.km;
        this.sentido = builder.sentido;
    }

    // Builder interno
    public static class Builder {
        private Long id;
        private LocalDate data;
        private LocalTime hora;
        private String placa;
        private String praca;
        private String rodovia;
        private String km;
        private String sentido;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder data(LocalDate data) {
            this.data = data;
            return this;
        }

        public Builder hora(LocalTime hora) {
            this.hora = hora;
            return this;
        }

        public Builder placa(String placa) {
            this.placa = placa;
            return this;
        }

        public Builder praca(String praca) {
            this.praca = praca;
            return this;
        }

        public Builder rodovia(String rodovia) {
            this.rodovia = rodovia;
            return this;
        }

        public Builder km(String km) {
            this.km = km;
            return this;
        }

        public Builder sentido(String sentido) {
            this.sentido = sentido;
            return this;
        }

        public RadarsDTO build() {
            return new RadarsDTO(this);
        }
    }

    // Método estático para iniciar a construção do objeto
    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getData() {
        return data;
    }

    public void setData(LocalDate data) {
        this.data = data;
    }

    public LocalTime getHora() {
        return hora;
    }

    public void setHora(LocalTime hora) {
        this.hora = hora;
    }

    public String getPlaca() {
        return placa;
    }

    public void setPlaca(String placa) {
        this.placa = placa;
    }

    public String getPraca() {
        return praca;
    }

    public void setPraca(String praca) {
        this.praca = praca;
    }

    public String getRodovia() {
        return rodovia;
    }

    public void setRodovia(String rodovia) {
        this.rodovia = rodovia;
    }

    public String getKm() {
        return km;
    }

    public void setKm(String km) {
        this.km = km;
    }

    public String getSentido() {
        return sentido;
    }

    public void setSentido(String sentido) {
        this.sentido = sentido;
    }
}
