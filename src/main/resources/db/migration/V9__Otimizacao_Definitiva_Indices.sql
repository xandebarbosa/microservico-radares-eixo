-- 1. Faxina: Remoção de índices duplicados ou apontando para colunas obsoletas
DROP INDEX IF EXISTS idx_radars_placa_trgm;   -- Duplicata do idx_radars_placa_gin (criado na V2)
DROP INDEX IF EXISTS idx_radars_praca_trgm;   -- Coluna 'praca' não é mais usada nos filtros principais
DROP INDEX IF EXISTS idx_radars_filtros_main; -- Criado na V2 usando 'praca', ficou obsoleto

-- 2. A "Bala de Prata": Índice perfeito para o DISTINCT ON e paginação
-- Reduz o tempo da sua busca de comboio de segundos para milissegundos
CREATE INDEX IF NOT EXISTS idx_radars_data_hora_placa
    ON radars_eixo (data DESC, hora DESC, placa);

-- 3. Índice Trigram (GIN) específico para Rodovia
-- Permite buscas ILIKE '%SP 284%' extremamente velozes
CREATE INDEX IF NOT EXISTS idx_radars_rodovia_trgm
    ON radars_eixo USING gin (rodovia gin_trgm_ops);

-- 4. Índice composto para buscas exatas
-- Muito útil para quando o TipoFonte = RADAR (busca rodovia + km exato)
CREATE INDEX IF NOT EXISTS idx_radars_rodovia_km_sentido
    ON radars_eixo (rodovia, km, sentido);