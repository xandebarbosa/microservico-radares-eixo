package com.coruja.entities;

import jakarta.persistence.*;
import lombok.*;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "localizacao_radar")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocalizacaoRadar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private  Long id;
    private String concessionaria;
    private String rodovia;
    private String km;
    private String praca;

    //Campo com as coordenadas geográficas
    //O código 4326 é o padrão universal para coordenadas de GPS (WGS 84)
    @Column(columnDefinition = "geography(Point,4326)")
    private Point localizacao;
}
