-- V8: Corrige tipo_fonte dos dados históricos
--
-- Problema: a migration V6 aplicou DEFAULT 'RECEBIDOS' em todos os registros
-- existentes, inclusive nos que vieram da pasta /radar (que têm KM preenchido).
--
-- Regra de negócio:
--   - Tem KM preenchido  → RADAR
--   - KM nulo ou vazio   → RECEBIDOS

-- 1. Marca como RADAR todos os registros que claramente têm KM
UPDATE radars_eixo
SET tipo_fonte = 'RADAR'
WHERE tipo_fonte = 'RECEBIDOS'
  AND km IS NOT NULL
  AND TRIM(km) <> '';

-- 2. Garante que registros sem KM fiquem como RECEBIDOS
UPDATE radars_eixo
SET tipo_fonte = 'RECEBIDOS'
WHERE (km IS NULL OR TRIM(km) = '')
  AND tipo_fonte = 'RADAR';

-- 3. Relatório de consistência (executado como comentário para referência)
-- SELECT tipo_fonte, COUNT(*) FROM radars_eixo GROUP BY tipo_fonte;