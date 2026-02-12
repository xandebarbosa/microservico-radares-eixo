-- Renomear a tabela de domínio
ALTER TABLE pracas RENAME TO rodovias;
-- Renomear a tabela de KMs
ALTER TABLE kms_praca RENAME TO kms_rodovia;
--Ajustar as colunas de chaves estrangeiras
ALTER TABLE kms_rodovia RENAME COLUMN praca_id TO rodovia_id;
