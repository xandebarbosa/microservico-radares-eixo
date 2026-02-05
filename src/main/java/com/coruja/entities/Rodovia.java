package com.coruja.entities;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "rodovias", indexes = {
        @Index(name = "idx_rodovia_nome", columnList = "nome", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rodovia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String nome;
}
