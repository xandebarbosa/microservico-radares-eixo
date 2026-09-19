package com.coruja.services.sftp;

import com.coruja.exceptions.SftpUnavailableException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Gerencia conexões SFTP — criação, reutilização e encerramento seguro.
 * Separa a responsabilidade de conexão das regras de negócio de processamento.
 */
@Component
@Slf4j
public class SftpConnectionManager {

    @Value("${sftp.host}") private String host;
    @Value("${sftp.port}") private int port;
    @Value("${sftp.user}") private String user;
    @Value("${sftp.pass}") private String pass;
    @Value("${sftp.timeout:30000}") private int timeout;
    @Value("${sftp.pool.size:5}") private int poolSize;

    private final JSch jsch = new JSch();
    private BlockingQueue<SftpConnection> connectionPool;

    public synchronized void initializePool() {
        if (connectionPool != null) return;
        connectionPool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            connectionPool.offer(createNewConnection());
        }
        log.info("[SFTP-Pool] Pool inicializado com {} conexões.", poolSize);
    }

    // 🟢 CIRCUIT BREAKER APLICADO AQUI
    @CircuitBreaker(name = "sftp-server", fallbackMethod = "fallbackBorrowConnection")
    public SftpConnection borrowConnection() {
        if (connectionPool == null) initializePool();
        try {
            SftpConnection conn = connectionPool.poll(45, TimeUnit.SECONDS);
            if (conn == null || !conn.isConnected()) {
                log.warn("[SFTP-Pool] Conexão morta ou pool vazio, recriando...");
                if (conn != null) conn.destroy();
                return createNewConnection();
            }
            return conn;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrompida ao buscar conexão", e);
        }
    }

    // 🟢 MÉTODO FALLBACK: É chamado automaticamente quando o disjuntor está ABERTO ou ocorre erro crítico
    public SftpConnection fallbackBorrowConnection(Throwable t) {
        log.error("[Circuit Breaker] Servidor SFTP inacessível. O circuito está ABERTO. Nova tentativa em breve. Motivo: {}", t.getMessage());
        // Lançamos uma exceção customizada (não checada) para abortar o ciclo graciosamente
        throw new SftpUnavailableException("Ciclo interrompido: Servidor SFTP em estado de falha/manutenção.");
    }

    public void returnConnection(SftpConnection conn) {
        if (conn != null && conn.isConnected()) {
            connectionPool.offer(conn);
        } else if (conn != null) {
            conn.destroy();
        }
    }

    private SftpConnection createNewConnection() {
        try {
            Session session = jsch.getSession(user, host, port);
            session.setPassword(pass);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setServerAliveInterval(15000);
            session.connect(timeout);

            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(timeout);
            return new SftpConnection(session, channel, this);
        } catch (JSchException e) {
            throw new RuntimeException("Falha crítica ao criar conexão SFTP", e);
        }
    }

    @PreDestroy
    public void closePool() {
        if (connectionPool != null) {
            connectionPool.forEach(SftpConnection::destroy);
        }
    }

    public record SftpConnection(Session session, ChannelSftp channel, SftpConnectionManager manager) implements AutoCloseable {
        public boolean isConnected() {
            return session != null && session.isConnected() && channel != null && channel.isConnected();
        }

        @Override
        public void close() {
            manager.returnConnection(this);
        }

        public void destroy() {
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }
}

