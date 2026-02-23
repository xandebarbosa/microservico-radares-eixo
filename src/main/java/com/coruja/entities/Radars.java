package com.coruja.entities;

import com.coruja.enuns.TipoFonte;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "radars_eixo",
        indexes = {
                @Index(name = "idx_radars_placa",       columnList = "placa"),
                @Index(name = "idx_radars_tipo_fonte",  columnList = "tipo_fonte"),
                @Index(name = "idx_radars_data_hora",   columnList = "data DESC, hora DESC"),
                @Index(name = "idx_radars_rodovia_km",  columnList = "rodovia, km")
        }
)
@AllArgsConstructor
@NoArgsConstructor
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

    /**
     * KM da rodovia. Pode ser nulo/vazio para registros da pasta /recebidos.
     */
    @Column(length = 20)
    private String km;

    @Column(length = 50)
    private String sentido;

    /**
     * Discriminador de origem: RECEBIDOS (sem KM) ou RADAR (com KM).
     * Nunca nulo — default RECEBIDOS para retrocompatibilidade.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_fonte", nullable = false, length = 20)
    @Builder.Default
    private TipoFonte tipoFonte = TipoFonte.RECEBIDOS;

    // Muitos registros de 'Radars' podem estar associados a Uma 'LocalizacaoRadar'.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "localizacao_id")// Nome da coluna da chave estrangeira no banco
    private  LocalizacaoRadar localizacao;




}
