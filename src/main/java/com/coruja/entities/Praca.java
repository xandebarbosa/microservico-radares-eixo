package com.coruja.entities;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pracas", indexes = {
        @Index(name = "idx_praca_nome", columnList = "nome", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Praca {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String nome;
}
