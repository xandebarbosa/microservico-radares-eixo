package com.coruja.dto;

public interface LocalizacaoRadarProjection {
    Long getId();
    String getConcessionaria();
    String getRodovia();
    String getKm();
    String getPraca();
    Double getLatitude();
    Double getLongitude();
}
