-- 1. Habilitar Extensões de Performance e Geoespacial
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm; -- Para busca rápida de placa (LIKE '%ABC%')
CREATE EXTENSION IF NOT EXISTS unaccent;

-- 2. Tabela de Localização (Geoespacial)
CREATE TABLE localizacao_radar (
    id BIGSERIAL PRIMARY KEY,
    concessionaria VARCHAR(255),
        rodovia VARCHAR(255),
        km VARCHAR(255),
        praca VARCHAR(255),

        -- Coluna geoespacial usando o tipo 'geography' para cálculos precisos (lat/lon)
        -- SRID 4326 é o padrão para WGS 84 (GPS)
        localizacao GEOGRAPHY(Point, 4326)
);

CREATE INDEX idx_localizacao_gist ON localizacao_radar USING GIST (localizacao);

-- 3. Tabelas de Domínio (Para os Filtros do Front-end)

-- Pracas
CREATE TABLE pracas (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(50) NOT NULL UNIQUE
);
CREATE INDEX idx_pracas_nome ON pracas(nome);

-- KMs da Pracas (Ex: 234, 110+500)
CREATE TABLE kms_praca (
    id BIGSERIAL PRIMARY KEY,
    valor VARCHAR(20) NOT NULL,
    praca_id BIGINT NOT NULL,
    CONSTRAINT fk_kms_praca FOREIGN KEY (praca_id) REFERENCES pracas(id) ON DELETE CASCADE
);
CREATE INDEX idx_kms_praca_valor ON kms_praca(valor);