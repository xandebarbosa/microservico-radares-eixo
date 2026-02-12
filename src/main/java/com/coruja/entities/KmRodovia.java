package com.coruja.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "kms_rodovia", indexes = {
        @Index(name = "idx_km_valor", columnList = "valor")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KmRodovia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String valor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rodovia_id", nullable = false)
    @JsonIgnore // <--- ADICIONADO: Impede o erro de serialização do Proxy
    @ToString.Exclude // <--- ADICIONADO: Evita loops no Lombok
    private Rodovia rodovia;
}
