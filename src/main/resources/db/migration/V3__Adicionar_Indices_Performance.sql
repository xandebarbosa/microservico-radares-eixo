-- 1. Cria índice GIN para a coluna placa (usado em busca-placa e busca-local)
CREATE INDEX IF NOT EXISTS idx_radars_placa_trgm ON radars_eixo USING gin (placa gin_trgm_ops);

-- 2. Cria índice GIN para a coluna praca (usado em busca-local)
CREATE INDEX IF NOT EXISTS idx_radars_praca_trgm ON radars_eixo USING gin (praca gin_trgm_ops);