-- V6: Adiciona coluna tipo_fonte para diferenciar registros de /recebidos e /radar
--
-- RECEBIDOS → arquivos da pasta /recebidos (somente rodovia, sem KM)
-- RADAR     → arquivos da pasta /radar e sub-pastas (rodovia + KM)
--
-- O default 'RECEBIDOS' garante retrocompatibilidade com dados existentes.

ALTER TABLE radars_eixo
    ADD COLUMN IF NOT EXISTS tipo_fonte VARCHAR(20) NOT NULL DEFAULT 'RECEBIDOS';

-- Índice para filtro por fonte — permite que queries direcionadas usem
-- apenas a partição relevante sem varredura completa.
CREATE INDEX IF NOT EXISTS idx_radars_tipo_fonte
    ON radars_eixo (tipo_fonte);

-- Índice composto para o caso de uso mais comum:
-- buscar por data + tipo_fonte (ex: "todos os registros de /radar de hoje")
CREATE INDEX IF NOT EXISTS idx_radars_data_tipo_fonte
    ON radars_eixo (data DESC, tipo_fonte);