package com.coruja.enuns;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Sentido {
    NORTE("Norte"),
    SUL("Sul"),
    LESTE("Leste"),
    OESTE("Oeste"),
    // Fallback para casos desconhecidos ou vazios
    NAO_IDENTIFICADO("N/A");

    private final String descricao;

    Sentido(String descricao) {
        this.descricao = descricao;
    }

    /**
     * @JsonValue diz ao Jackson para usar esse valor ao gerar o JSON.
     * Ex: Ao enviar para o front, vai sair "Leste" e não "LESTE".
     */
    @JsonValue
    public String getDescricao() {
        return descricao;
    }

    /**
     * @JsonCreator permite que o Jackson leia o JSON (ex: "Leste", "LESTE" ou "leste")
     * e encontre o Enum correto, ignorando maiúsculas/minúsculas.
     */
    @JsonCreator
    public static Sentido fromString(String value) {
        if (value == null || value.isBlank()) {
            return null; // ou NAO_IDENTIFICADO se preferir não permitir nulos
        }

        for (Sentido s : Sentido.values()) {
            // Verifica se bate com a descrição ("Leste") ou com o nome ("LESTE")
            if (s.descricao.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }

        throw new IllegalArgumentException("Sentido inválido: " + value);
    }
}
