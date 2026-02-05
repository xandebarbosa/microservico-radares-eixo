package com.coruja.dto;

public interface LocalizacaoRadarProjection {
    Long getId();
    String getConcessionaria();
    String getPraca();
    String getRodovia();
    String getKm();
    Double getLatitude();
    Double getLongitude();
}
