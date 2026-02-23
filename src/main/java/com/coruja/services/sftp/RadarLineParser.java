package com.coruja.services.sftp;

import com.coruja.entities.LocalizacaoRadar;
import com.coruja.entities.Radars;
import com.coruja.enuns.TipoFonte;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converte uma linha CSV em uma entidade {@link Radars}.
 *
 * <p>O comportamento difere conforme o {@link TipoFonte}:
 * <ul>
 *   <li>{@code RECEBIDOS} — extrai apenas rodovia (sem KM).</li>
 *   <li>{@code RADAR}     — extrai rodovia E KM da coluna de localização.</li>
 * </ul>
 */
@Component
@Slf4j
public class RadarLineParser {

    private static final Pattern PATTERN_RODOVIA   = Pattern.compile("([A-Z]{2,3}-?\\d{3}(?:/\\d{3})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_KM        = Pattern.compile("km[:\\s]*(\\d+)",    Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_METROS    = Pattern.compile("metros[:\\s]*(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * Faz o parse de uma linha CSV.
     *
     * @param linha        Linha do arquivo no formato {@code dataHora;placa;localizacao;sentido}.
     * @param tipoFonte    Origem do arquivo (RECEBIDOS ou RADAR).
     * @param localCache   Cache de localização por chave {@code rodovia|km}.
     * @param pracaCache   Cache de localização por nome de praça normalizado.
     * @return {@link Optional} com o radar parseado, vazio se a linha for inválida.
     */
    public Optional<Radars> parseLine(
            String linha,
            TipoFonte tipoFonte,
            Map<String, LocalizacaoRadar> localCache,
            Map<String, LocalizacaoRadar> pracaCache) {

        String[] campos = linha.split(";", -1);
        if (campos.length < 4) return Optional.empty();

        try {
            String dataHoraStr       = campos[0].trim();
            String placa             = normalizarPlaca(campos[1].trim());
            String localizacaoBruta  = campos[2].trim();
            String sentido           = campos[3].trim().replaceAll("\\s+", " ");

            if (placa.length() < 7 && !placa.equals("N/I")) return Optional.empty();

            String[] dhParts = dataHoraStr.split("T");
            if (dhParts.length < 2) return Optional.empty();

            LocalDate data = LocalDate.parse(dhParts[0]);
            LocalTime hora = LocalTime.parse(dhParts[1].replace("-", ":").split("\\.")[0]);

            String rodoviaFinal;
            String kmFinal;
            LocalizacaoRadar localizacao;

            if (tipoFonte == TipoFonte.RADAR) {
                rodoviaFinal = extrairRodovia(localizacaoBruta);
                kmFinal      = extrairKm(localizacaoBruta);
                localizacao  = localCache.get(chaveCache(rodoviaFinal, kmFinal));
            } else {
                // RECEBIDOS: a localização pode ser o nome da praça ou a rodovia
                String chave = normalizar(localizacaoBruta);
                localizacao  = pracaCache.get(chave);

                if (localizacao != null) {
                    rodoviaFinal = localizacao.getRodovia();
                    kmFinal      = ""; // sem KM neste fluxo
                } else {
                    rodoviaFinal = localizacaoBruta.length() > 100
                            ? localizacaoBruta.substring(0, 100)
                            : localizacaoBruta;
                    kmFinal = "";
                }
            }

            Radars radar = Radars.builder()
                    .data(data)
                    .hora(hora)
                    .placa(placa)
                    .rodovia(rodoviaFinal)
                    .km(kmFinal)
                    .sentido(sentido)
                    .tipoFonte(tipoFonte)
                    .localizacao(localizacao)
                    .build();

            return Optional.of(radar);

        } catch (Exception e) {
            log.trace("[Parser] Linha ignorada (erro: {}): {}", e.getMessage(), linha);
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AUXILIARES
    // ─────────────────────────────────────────────────────────────

    public String chaveCache(String rodovia, String km) {
        return normalizar(rodovia) + "|" + normalizar(km);
    }

    public String normalizar(String texto) {
        if (texto == null) return "";
        String nfd = Normalizer.normalize(texto, Normalizer.Form.NFD);
        return Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
                .matcher(nfd).replaceAll("").trim().toUpperCase();
    }

    private String extrairRodovia(String texto) {
        Matcher m = PATTERN_RODOVIA.matcher(texto);
        return m.find() ? m.group(1).toUpperCase() : "";
    }

    private String extrairKm(String texto) {
        Matcher mk = PATTERN_KM.matcher(texto);
        Matcher mm = PATTERN_METROS.matcher(texto);

        String vk = mk.find() ? String.format("%03d", Integer.parseInt(mk.group(1))) : "000";
        String vm = mm.find() ? String.format("%03d", Integer.parseInt(mm.group(1))) : "000";

        return (vk.equals("000") && vm.equals("000")) ? "" : vk + "+" + vm;
    }

    private String normalizarPlaca(String placa) {
        if (placa == null || placa.isBlank()) return "N/I";
        String limpa = placa.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
        if (limpa.length() > 7) {
            log.warn("[Parser] Placa truncada: '{}' → '{}'", placa, limpa.substring(0, 7));
            return limpa.substring(0, 7);
        }
        return limpa;
    }
}
