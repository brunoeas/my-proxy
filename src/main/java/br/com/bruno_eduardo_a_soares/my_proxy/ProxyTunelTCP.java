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

    private NetClient clientTCP = null;

    @PostConstruct
    public void initProxyTunelTCP() {
        this.clientTCP = this.vertx.createNetClient(new NetClientOptions().setTcpNoDelay(true));
    }

    public void openTcpTunnel(final HttpServerRequest requisicaoOriginal, final HostPort hostPort) {
        try {
            Objects.requireNonNull(hostPort);
            if (this.clientTCP == null) {
                log.error("Erro ao tratar Túnel TCP: TCP Client do Vertx está null.");
                requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                return;
            }

            // Cria uma conexão TCP com o server de destino.
            // Nessa ordem: Abre um socket TCP. Executa o handshake TCP padrão. Se der certo, entrega um NetSocket.
            // No contexto desse código o `NetClient clientTCP` não está configurado para fazer handshake TLS.
            // O handshake TLS será feito entre cliente e servidor, esse Proxy apenas repassa os bytes.
            this.clientTCP.connect(hostPort.port(), hostPort.host(), futureServerSocket -> {
                if (futureServerSocket.succeeded()) {
                    // Depois de confirmar que a conexão TCP com o server foi estabelecida responde 200 ao Client (HTTP/1.1 200 Connection Established).
                    // Significa que a função "HttpServerRequest.toNetSocket()" vai responder "HTTP/1.1 200 Connection Established" ao
                    // Cliente e também vai criar o Socket TCP para o lado do Cliente se comunicar com o server de destino.
                    // Então enquanto a conexão TCP está ativa é criado um Socket TCP para o cliente,
                    // E esta é uma das caracteristica que faz isso ser considerado um Túnel TCP.
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
                                log.error("Erro ao tratar Túnel TCP: Client socket error", t);
                                serverSocket.close();
                            });
                            serverSocket.exceptionHandler(t -> {
                                log.error("Erro ao tratar Túnel TCP: Server socket error", t);
                                clientSocket.close();
                            });

                            log.infof("✅✅✅ URI: \"%s\" - Fim do processamento do Túnel TCP. 🔒 🔒 🔒", requisicaoOriginal.uri());

                        } else {
                            log.error("Erro ao tratar Túnel TCP: Failed to obtain client net socket.", clientSocketRes.cause());
                            futureServerSocket.result().close();
                            requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                        }
                    });

                } else {
                    log.error("Erro ao tratar Túnel TCP: Failed to connect to destination.", futureServerSocket.cause());
                    requisicaoOriginal.response().setStatusCode(502).end("Bad Gateway\n");
                }
            });

        } catch (final Exception e) {
            log.error("Erro ao tratar Túnel TCP: Erro inesperado.", e);
            requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
        }
    }

}
