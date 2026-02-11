package com.coruja.schedulers;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LocalizacaoScheduler {
    private static final Logger logger = LoggerFactory.getLogger(LocalizacaoScheduler.class);
    private static final int BATCH_SIZE = 1000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Este método roda assim que o Spring inicia.
    // Se não aparecer no log, a classe não está sendo lida (erro de pacote/scan).
    @PostConstruct
    public void init() {
        log.info(">>> LocalizacaoScheduler EIXO carregado com sucesso! O primeiro job rodará em breve.");
    }

    /**
     * Executa a cada 5 minutos (300.000 ms).
     * Atualiza a coluna localizacao_id na tabela radars_cart
     * cruzando dados com a tabela localizacao_radar.
     */

    @Scheduled(fixedRate = 300000)
    public void vincularLocalizacoesEixo() {
        log.info("Iniciando job de vinculação de localizações Concessionária Eixo...");

        String sqlBatch = """
            WITH pending_batch AS (
                SELECT id, data, praca
                FROM radars_eixo
                WHERE localizacao_id IS NULL
                LIMIT ?
            ),
            match_update AS (
                SELECT
                    pb.id AS radar_id,
                    pb.data AS radar_data,
                    lr.id AS loc_id
                FROM pending_batch pb
                JOIN localizacao_radar lr
                    -- Compara as praças ignorando acentos e espaços extras
                    ON unaccent(TRIM(UPPER(pb.praca))) = unaccent(TRIM(UPPER(lr.praca)))
                    OR unaccent(TRIM(UPPER(pb.praca))) ILIKE CONCAT('%', unaccent(TRIM(UPPER(lr.praca))), '%')
            )
            UPDATE radars_eixo rc
            SET localizacao_id = mu.loc_id
            FROM match_update mu
            WHERE rc.id = mu.radar_id
              AND rc.data = mu.radar_data;
        """;

        try {
            long inicio = System.currentTimeMillis();
            long totalAtualizado = 0;
            int linhasAfetadas;

            do {
                linhasAfetadas = jdbcTemplate.update(sqlBatch, BATCH_SIZE);
                totalAtualizado += linhasAfetadas;

                if (linhasAfetadas > 0) {
                    logger.debug("Lote processado: {} radares da Concessionária Eixo vinculados.", linhasAfetadas);
                    Thread.sleep(50); // Pausa leve para respiro do DB
                }
            } while (linhasAfetadas >= BATCH_SIZE);

            long fim = System.currentTimeMillis();
            if (totalAtualizado > 0) {
                logger.info("✅ Sucesso! Total de {} radares da Concessionária Eixo vinculados em {} ms.", totalAtualizado, (fim - inicio));
            } else {
                logger.info("🏁 Nenhum novo vínculo encontrado com os critérios atuais - Concessionária Eixo.");
            }

        } catch (Exception e) {
            logger.error("❌ Erro crítico no job de localização - Concessionária Eixo: ", e);
        }

    }
}
