package com.coruja.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "radars_eixo",
        indexes = {
                @Index(name = "idx_radars_placa", columnList = "placa")
        }
)
@AllArgsConstructor
@Getter
@Setter
@Builder
public class Radars {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) // Garante que o campo não seja nulo no banco de dados
    private LocalDate data;

    @Column(nullable = false) // Garante que o campo não seja nulo no banco de dados
    private LocalTime hora;

    @Column(nullable = false, length = 7) // Exemplo: ABC1234 (7 caracteres)
    private String placa;

    @Column(name = "rodovia")
    private String rodovia;
    private String km;
    private String sentido;

    // Muitos registros de 'Radars' podem estar associados a Uma 'LocalizacaoRadar'.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "localizacao_id")// Nome da coluna da chave estrangeira no banco
    private  LocalizacaoRadar localizacao;

    // Construtor padrão (obrigatório para o Hibernate)
    public Radars() {
    }

    public Radars(LocalDate data, LocalTime hora, String placa, String rodovia, String km, String sentido, LocalizacaoRadar localizacao) {
        this.data = data;
        this.hora = hora;
        this.placa = placa;
        this.rodovia = rodovia;
        this.km = km != null ? km : "";
        this.sentido = sentido;
        this.localizacao = localizacao;
    }


}
