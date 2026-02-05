-- 1. Criação da Tabela Mestra Particionada
-- Importante: A Primary Key DEVE conter a coluna de particionamento (data)
CREATE TABLE radars_cart (
    id BIGSERIAL,
    data DATE NOT NULL,
    hora TIME NOT NULL,
    placa VARCHAR(7) NOT NULL,
    praca VARCHAR(255),
    rodovia VARCHAR(255) NOT NULL,
    km VARCHAR(255) NOT NULL,
    sentido VARCHAR(255) NOT NULL,
    localizacao_id BIGINT,

    -- Constraint da Chave Primária Composta (Necessário para o particionamento funcionar)
    CONSTRAINT pk_radars_cart PRIMARY KEY (id, data),

    -- Foreign Key
    CONSTRAINT fk_radars_localizacao
        FOREIGN KEY (localizacao_id)
        REFERENCES localizacao_radar(id)
) PARTITION BY RANGE (data);

-- 2. Criação das Partições (Ajustado para o ano atual: 2026)

-- HISTÓRICO: Tudo antes de 2025 (Ex: 2024, 2023...)
-- Dados muito antigos ficam aqui e não atrapalham a busca do dia a dia.
CREATE TABLE radars_cart_history PARTITION OF radars_cart
    FOR VALUES FROM (MINVALUE) TO ('2025-01-01');

-- ANO PASSADO (2025)
-- Útil para relatórios comparativos (Year-over-Year)
CREATE TABLE radars_cart_2025 PARTITION OF radars_cart
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');

-- ANO CORRENTE (2026) - ONDE A MÁGICA ACONTECE
-- 99% das suas buscas automáticas cairão nesta tabela menor e rápida.
CREATE TABLE radars_cart_2026 PARTITION OF radars_cart
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

-- PRÓXIMO ANO (2027)
-- Já deixamos pronto para não parar o sistema no Réveillon.
CREATE TABLE radars_cart_2027 PARTITION OF radars_cart
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');

-- DEFAULT (Segurança)
-- Se chegar uma data maluca (ex: ano 2090), cai aqui e não dá erro de insert.
CREATE TABLE radars_cart_default PARTITION OF radars_cart DEFAULT;


-- 3. Índices de Alta Performance (Otimizados)

-- A. Busca de Placa com 'LIKE' (pg_trgm)
-- Permite buscar '%ABC%' instantaneamente dentro da partição do ano.
CREATE INDEX idx_radars_placa_gin ON radars_cart USING GIN (placa gin_trgm_ops);

-- B. Índice Composto para Filtros (Cobre a busca por Local + Data + Hora)
-- Ordem estratégica: Rodovia (filtro macro) -> Km -> Data -> Hora
CREATE INDEX idx_radars_filtros_main ON radars_cart (rodovia, km, data, hora);

-- C. Índice Cronológico
-- Para ordenar "os mais recentes primeiro" sem fazer "sort" em memória.
CREATE INDEX idx_radars_data_hora ON radars_cart (data DESC, hora DESC);

-- D. Índice de Join
CREATE INDEX idx_radars_localizacao_id ON radars_cart(localizacao_id);