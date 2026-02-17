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

import java.util.Objects;

/**
 * Forward Proxy HTTP operando em modo túnel TCP.
 * Para method HTTP CONNECT o Proxy cria um túnel TCP para trafegar os bytes do cliente (que também podem ser HTTP).
 * - Abre conexão TCP
 * - Para de interpretar HTTP
 * - Só encaminha bytes nas duas direções sem ler
 */
@JBossLog
@ApplicationScoped
public class ProxyTunelTCP {

    @Inject
    Vertx vertx;

    private NetClient clientHTTPS = null;

    @PostConstruct
    public void initProxyHTTPS() {
        this.clientHTTPS = this.vertx.createNetClient(new NetClientOptions().setTcpNoDelay(true));
    }

    public void openTcpTunnel(final HttpServerRequest requisicaoOriginal, final HostPort hostPort) {
        try {
            Objects.requireNonNull(hostPort);
            if (this.clientHTTPS == null) {
                log.error("Erro ao tratar requisição HTTPS: TCP Client do Vertx está null.");
                requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                return;
            }

            // Cria uma conexão TCP com o server de destino
            this.clientHTTPS.connect(hostPort.port(), hostPort.host(), futureServerSocket -> {
                if (futureServerSocket.succeeded()) {
                    // Depois de confirmar que a conexão TCP com o server foi estabelecida responde 200 ao Client (HTTP/1.1 200 Connection Established)
                    // Então enquanto a conexão TCP está ativa, é criado um Socket TCP para o cliente poder se comunicar com o Socket TCP do server
                    requisicaoOriginal.toNetSocket(clientSocketRes -> {
                        if (clientSocketRes.succeeded()) {
                            // Socket do Client/Navegador que enviou a requisição que o Proxy capturou
                            final NetSocket clientSocket = clientSocketRes.result();
                            // Socket do Server que vai receber os dados HTTP
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

                            log.infof("✅✅✅ URI: \"%s\" - Fim do processamento da requisição HTTPS. 🔒 🔒 🔒", requisicaoOriginal.uri());

                        } else {
                            log.error("Erro ao tratar requisição HTTPS: Failed to obtain client net socket.", clientSocketRes.cause());
                            futureServerSocket.result().close();
                            requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
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

}
