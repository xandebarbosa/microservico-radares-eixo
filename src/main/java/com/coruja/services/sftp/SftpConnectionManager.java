package com.coruja.services.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;

/**
 * Gerencia conexões SFTP — criação, reutilização e encerramento seguro.
 * Separa a responsabilidade de conexão das regras de negócio de processamento.
 */
@Component
@Slf4j
public class SftpConnectionManager {

    @Value("${sftp.host}")       private String host;
    @Value("${sftp.port}")       private int    port;
    @Value("${sftp.user}")       private String user;
    @Value("${sftp.pass}")       private String pass;
    @Value("${sftp.timeout:30000}") private int timeout;

    /**
     * Abre uma sessão e um canal SFTP, encapsulados em {@link SftpConnection}.
     * Utilize em try-with-resources para garantir fechamento.
     */
    public SftpConnection open() throws JSchException {
        log.info("[SFTP] Abrindo conexão com {}@{}:{}", user, host, port);
        JSch jsch = new JSch();
        Session session = jsch.getSession(user, host, port);
        session.setPassword(pass);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(timeout);

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();

        log.info("[SFTP] Conexão estabelecida.");
        return new SftpConnection(session, channel);
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * Encapsula um par (Session + ChannelSftp) com suporte a AutoCloseable.
     */
    public record SftpConnection(Session session, ChannelSftp channel) implements Closeable {

        public boolean isConnected() {
            return session.isConnected() && channel.isConnected();
        }

        @Override
        public void close() throws IOException {
            try {
                if (channel.isConnected()) channel.disconnect();
            } finally {
                if (session.isConnected()) session.disconnect();
            }
        }
    }
}

