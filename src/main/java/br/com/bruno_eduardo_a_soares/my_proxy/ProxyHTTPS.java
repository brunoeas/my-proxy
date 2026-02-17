package br.com.bruno_eduardo_a_soares.my_proxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class ProxyHTTPS {

    @Inject
    Vertx vertx;

    private NetClient clientHTTPS = null;

    @PostConstruct
    public void initProxyHTTPS() {
        this.clientHTTPS = this.vertx.createNetClient(new NetClientOptions().setTcpNoDelay(true));
    }

    public void handleConnectHttps(final HttpServerRequest requisicaoOriginal) {
        try {
            if (this.clientHTTPS == null) {
                log.error("Erro ao tratar requisição HTTPS: TCP Client está null.");
                requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                return;
            }

            // Extrai da requisição original do Cliente/Navegador o Host e a Porta do server de destino
            final HostPort hostPort = this.extractHostAndPortHTTPS(requisicaoOriginal);
            if (hostPort == null) {
                return;
            }

            // Cria uma conexão TCP com o server de destino
            this.clientHTTPS.connect(hostPort.port(), hostPort.host(), futureServerSocket -> {
                if (futureServerSocket.succeeded()) {
                    // Depois de confirmar que a conexão TCP com o server foi estabelecida responde 200 ao Client (HTTP/1.1 200 Connection Established)
                    // Então enquanto a conexão TCP está ativa, é criado um Socket TCP para o cliente poder se comunicar com o Socket TCP do server
                    requisicaoOriginal.toNetSocket(clientSocketRes -> {
                        if (clientSocketRes.succeeded()) {
                            final NetSocket clientSocket = clientSocketRes.result();
                            final NetSocket serverSocket = futureServerSocket.result();

                            // repassa dados nas duas direções
                            clientSocket.handler(serverSocket::write);
                            serverSocket.handler(clientSocket::write);

                            // fecha o outro lado se um fechar
                            clientSocket.closeHandler(_ -> serverSocket.close());
                            serverSocket.closeHandler(_ -> clientSocket.close());

                            clientSocket.exceptionHandler(t -> {
                                log.error("Erro ao tratar requisição HTTPS: Client socket error", t);
                                serverSocket.close();
                            });
                            serverSocket.exceptionHandler(t -> {
                                log.error("Erro ao tratar requisição HTTPS: Server socket error", t);
                                clientSocket.close();
                            });

                            log.info("✅✅✅ Fim do processamento da requisição HTTPS: " + requisicaoOriginal.uri());

                        } else {
                            log.error("Erro ao tratar requisição HTTPS: Failed to obtain client net socket.", clientSocketRes.cause());
                            futureServerSocket.result().close();
                        }
                    });

                } else {
                    log.error("Erro ao tratar requisição HTTPS: Failed to connect to destination.", futureServerSocket.cause());
                    requisicaoOriginal.response().setStatusCode(502).end("Bad Gateway\n");
                }
            });

        } catch (final Exception e) {
            log.error("Erro ao tratar requisição HTTPS: Desconhecido.", e);
            requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
        }
    }

    private HostPort extractHostAndPortHTTPS(final HttpServerRequest requisicaoOriginal) {
        final String authority = requisicaoOriginal.uri(); // normalmente host:port
        final HostPort hostPort = this.parseAuthority(authority);
        if (hostPort == null) {
            log.error("Erro ao tratar requisição HTTPS: Não foi possível extrair o Host e a Porta.");
            requisicaoOriginal.response().setStatusCode(400).end("Bad CONNECT authority\n");
            return null;
        } else {
            return hostPort;
        }
    }

    // Suporte básico para host:port e [ipv6]:port
    private HostPort parseAuthority(final String authority) {
        if (authority == null || authority.isEmpty()) {
            return null;
        }

        try {
            if (authority.startsWith("[")) {
                // [ipv6]:port
                final int close = authority.indexOf(']');
                if (close == -1) return null;
                final String host = authority.substring(1, close);
                final int colon = authority.indexOf(':', close);
                final int port = colon == -1 ? 443 : Integer.parseInt(authority.substring(colon + 1));
                return new HostPort(host, port);
            } else {
                final int colon = authority.lastIndexOf(':');
                if (colon == -1) return new HostPort(authority, 443);
                final String host = authority.substring(0, colon);
                final int port = Integer.parseInt(authority.substring(colon + 1));
                return new HostPort(host, port);
            }

        } catch (final Exception e) {
            log.error("Erro ao tratar requisição HTTPS: Failed to parse authority: " + authority, e);
            return null;
        }
    }

}
