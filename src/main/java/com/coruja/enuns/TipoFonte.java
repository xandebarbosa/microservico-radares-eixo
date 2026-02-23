package com.coruja.enuns;

/**
 * Discriminador que identifica a origem do arquivo SFTP.
 *
 * <ul>
 *   <li>{@code RECEBIDOS} — arquivos da pasta {@code /recebidos}.
 *       Contêm apenas o nome da rodovia, sem KM.</li>
 *   <li>{@code RADAR} — arquivos da pasta {@code /radar} (e sub-pastas).
 *       Contêm rodovia <b>e</b> KM.</li>
 * </ul>
 */
public enum TipoFonte {
    RECEBIDOS,
    RADAR
}
