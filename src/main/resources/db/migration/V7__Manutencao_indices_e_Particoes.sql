-- V7: Manutenção de partições e índices adicionais de performance
--
-- 1. Partição 2028 (preventiva)
CREATE TABLE IF NOT EXISTS radars_eixo_2028 PARTITION OF radars_eixo
    FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');

-- 2. Índice composto rodovia + tipo_fonte para filtros de busca local
--    Cobre: WHERE tipo_fonte = ? AND rodovia ILIKE ?
CREATE INDEX IF NOT EXISTS idx_radars_rodovia_tipo_fonte
    ON radars_eixo (tipo_fonte, rodovia);