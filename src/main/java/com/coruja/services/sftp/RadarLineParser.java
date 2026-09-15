package com.coruja.services.sftp;

import com.coruja.entities.LocalizacaoRadar;
import com.coruja.entities.Radars;
import com.coruja.enuns.TipoFonte;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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

    private static final Pattern PLACA_MERCOSUL_PATTERN = Pattern.compile(
            "^(" +
                    "[A-Z]{3}[0-9]{4}|" +          // BR Antigo / UY Mercosul
                    "[A-Z]{3}[0-9][A-Z][0-9]{2}|" + // BR Carro Mercosul
                    "[A-Z]{3}[0-9]{2}[A-Z][0-9]|" + // BR Moto Mercosul
                    "[A-Z]{2}[0-9]{3}[A-Z]{2}|" +   // AR Carro Mercosul
                    "[A-Z][0-9]{3}[A-Z]{3}|" +      // AR Moto Mercosul
                    "[A-Z]{3}[0-9]{3}|" +           // AR Antigo (6 caracteres)
                    "[A-Z]{4}[0-9]{3}|" +           // PY Carro Mercosul (ex: AAGY936)
                    "[0-9]{3}[A-Z]{4}" +            // PY Moto Mercosul
                    ")$"
    );

    // Regex pré-compiladas para otimização de CPU no processamento em lote
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern MULTIPLE_SPACES_PATTERN = Pattern.compile("\\s+");
    private static final DateTimeFormatter DATE_FORMATTER_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Faz o parse de uma linha CSV.
     *
     * @param linha      Linha do arquivo no formato {@code dataHora;placa;localizacao;sentido}.
     * @param tipoFonte  Origem do arquivo (RECEBIDOS ou RADAR).
     * @param localCache Cache de localização por chave {@code rodovia|km}.
     * @return {@link Optional} com o radar parseado, vazio se a linha for inválida.
     */
    public Optional<Radars> parseLine(
            String linha,
            TipoFonte tipoFonte,
            Map<String, LocalizacaoRadar> localCache) {

        if (linha == null || linha.isBlank()) {
            return Optional.empty();
        }

        try {
            // Quebra por ponto e vírgula OU tabulação
            String[] campos = linha.split("[;\t]", -1);

            String dataStr;
            String horaStr;
            String placaBruta;
            String localizacaoBruta;
            String sentidoBruto;

            // Verifica se a data e a hora vieram separadas em colunas distintas (formato legado/tabulado)
            if (campos[0].length() <= 10 && campos.length >= 5 && campos[1].contains(":")) {
                dataStr          = campos[0].trim();
                horaStr          = campos[1].trim();
                placaBruta       = campos[2].trim();
                localizacaoBruta = campos[3].trim();
                sentidoBruto     = campos[4].trim();
            }
            // Senão, é o formato EIXOSP novo (onde Data e Hora estão sempre juntas na coluna 0)
            else {
                if (campos.length < 4) return Optional.empty(); // O mínimo são 4 colunas (Recebidos)

                String dataHoraBruta = campos[0].trim();

                if (dataHoraBruta.contains("T")) {
                    // Formato RADAR: "2026-08-28T07-50-05"
                    String[] dhParts = dataHoraBruta.split("T");
                    dataStr = dhParts[0];
                    horaStr = dhParts[1].replace("-", ":");
                } else {
                    // Formato RECEBIDOS: "2026-08-28 17:09:35.000"
                    String[] dhParts = dataHoraBruta.split("\\s+");
                    dataStr = dhParts[0];
                    horaStr = dhParts[1];
                }

                placaBruta       = campos[1].trim();
                localizacaoBruta = campos[2].trim();
                sentidoBruto     = campos[3].trim();
            }

            // Normaliza a placa
            String placa = normalizarPlaca(placaBruta);

            // Otimização: usa regex compilada para remover múltiplos espaços em vez de compilar a cada iteração
            String sentido = MULTIPLE_SPACES_PATTERN.matcher(sentidoBruto).replaceAll(" ").replace("↑", "").trim();

            // Converte as siglas para o nome completo (N -> Norte, L -> Leste, etc)
            if (sentido.length() == 1) {
                switch (sentido.toUpperCase()) {
                    case "N": sentido = "Norte"; break;
                    case "S": sentido = "Sul"; break;
                    case "L": sentido = "Leste"; break;
                    case "O": sentido = "Oeste"; break;
                }
            }

            // Validação de formato Mercosul
            if (!placa.equals("N/I") && !isPlacaValida(placa)) {
                log.trace("Placa em formato desconhecido descartada: {}", placa);
                return Optional.empty();
            }

            // Parse da Data
            LocalDate data = dataStr.contains("/")
                    ? LocalDate.parse(dataStr, DATE_FORMATTER_BR)
                    : LocalDate.parse(dataStr);

            // Otimização: limpa os milissegundos ".000" sem fazer uso de split (zero alocação de array na Heap)
            int dotIndex = horaStr.indexOf('.');
            if (dotIndex != -1) {
                horaStr = horaStr.substring(0, dotIndex);
            }
            LocalTime hora = LocalTime.parse(horaStr);

            // Extração de Localização baseada na Fonte
            String rodoviaFinal;
            String kmFinal;

            if (tipoFonte == TipoFonte.RADAR) {
                rodoviaFinal = extrairRodovia(localizacaoBruta);
                kmFinal      = extrairKm(localizacaoBruta);

                if (rodoviaFinal.isBlank()) {
                    log.warn("[Parser] ALERTA: Regex não extraiu a rodovia de: '{}'.", localizacaoBruta);
                }
            } else {
                // Remove a palavra "Principal " e arruma possíveis espaços duplos
                localizacaoBruta = localizacaoBruta.replace("Principal ", "").replaceAll("\\s+", " ").trim();

                rodoviaFinal = localizacaoBruta.length() > 255
                        ? localizacaoBruta.substring(0, 255)
                        : localizacaoBruta;
                kmFinal = "";
            }

            // Constrói e retorna a entidade
            Radars radar = Radars.builder()
                    .data(data)
                    .hora(hora)
                    .placa(placa)
                    .rodovia(rodoviaFinal)
                    .km(kmFinal)
                    .sentido(sentido)
                    .tipoFonte(tipoFonte)
                    .build();

            return Optional.of(radar);

        } catch (Exception e) {
            log.warn("[Parser] Linha corrompida ignorada (erro: {}): {}", e.getMessage(), linha);
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
        // Otimização: utiliza o Pattern pré-compilado para evitar sobrecarga de CPU
        return DIACRITICS_PATTERN.matcher(nfd).replaceAll("").trim().toUpperCase();
    }

    private String extrairRodovia(String texto) {
        Matcher m = PATTERN_RODOVIA.matcher(texto);
        return m.find() ? m.group(1).toUpperCase() : "";
    }

    private String extrairKm(String texto) {
        Matcher mk = PATTERN_KM.matcher(texto);
        Matcher mm = PATTERN_METROS.matcher(texto);

        String vk = mk.find() ? formatPadLeft3(mk.group(1)) : "000";
        String vm = mm.find() ? formatPadLeft3(mm.group(1)) : "000";

        return (vk.equals("000") && vm.equals("000")) ? "" : vk + "+" + vm;
    }

    /**
     * Otimização: formatação de preenchimento manual ultrarrápida.
     * Evita o overhead severo do String.format("%03d") ao processar milhões de registros.
     */
    private String formatPadLeft3(String val) {
        if (val == null) return "000";
        if (val.length() == 1) return "00" + val;
        if (val.length() == 2) return "0" + val;
        return val;
    }

    private String normalizarPlaca(String placa) {
        if (placa == null || placa.isBlank()) return "N/I";
        String limpa = placa.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

        if (limpa.isBlank()) return "N/I";

        if (limpa.length() > 7) {
            log.warn("[Parser] Placa truncada: '{}' → '{}'", placa, limpa.substring(0, 7));
            return limpa.substring(0, 7);
        }
        return limpa;
    }

    private boolean isPlacaValida(String placa) {
        return PLACA_MERCOSUL_PATTERN.matcher(placa).matches();
    }
}
