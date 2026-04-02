-- V8: Tabela de rastreamento persistente de arquivos SFTP
--
-- Substitui os sets in-memory do SftpDownloader, tornando o controle
-- de duplicatas resiliente a restarts do container.
--
-- Ciclo de vida: PENDENTE → BAIXADO → PROCESSADO | ERRO
-- Arquivos com ERRO e tentativas < max podem ser retentados automaticamente.

CREATE TABLE arquivo_sftp_processado (
    id              BIGSERIAL PRIMARY KEY,
    nome_arquivo    VARCHAR(255) NOT NULL,
    tipo_fonte      VARCHAR(20)  NOT NULL,              -- RECEBIDOS | RADAR
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDENTE', -- PENDENTE | BAIXADO | PROCESSADO | ERRO
    registros_salvos INTEGER,                           -- Preenchido após processamento
    baixado_em      TIMESTAMP,                          -- Quando foi baixado do SFTP
    processado_em   TIMESTAMP,                          -- Quando o parse+insert terminou
    mensagem_erro   VARCHAR(1000),                      -- Preenchido apenas em caso de ERRO
    tentativas      INTEGER NOT NULL DEFAULT 0,         -- Para lógica de retry

    CONSTRAINT uq_arquivo_nome UNIQUE (nome_arquivo)
);

-- Índice para verificação rápida de duplicata (hot path no download)
CREATE INDEX idx_arquivo_nome         ON arquivo_sftp_processado (nome_arquivo);

-- Índice para busca de arquivos elegíveis para retry
CREATE INDEX idx_arquivo_status       ON arquivo_sftp_processado (status)
    WHERE status = 'ERRO';

-- Índice para relatório/monitoramento por fonte e data
CREATE INDEX idx_arquivo_tipo_data    ON arquivo_sftp_processado (tipo_fonte, processado_em DESC);

-- Comentários para documentação
COMMENT ON TABLE  arquivo_sftp_processado                IS 'Rastreamento persistente de arquivos SFTP baixados e processados';
COMMENT ON COLUMN arquivo_sftp_processado.nome_arquivo   IS 'Nome exato do arquivo remoto (ex: EIXOSP_2026-02-23T14-38-176641.csv)';
COMMENT ON COLUMN arquivo_sftp_processado.tipo_fonte     IS 'RECEBIDOS = pasta /recebidos (sem KM) | RADAR = pasta /radar (com KM)';
COMMENT ON COLUMN arquivo_sftp_processado.registros_salvos IS 'Quantas linhas do arquivo foram persistidas na tabela radars_eixo';
COMMENT ON COLUMN arquivo_sftp_processado.tentativas     IS 'Contador de tentativas; arquivos com ERRO são retentados até atingir o limite configurado';