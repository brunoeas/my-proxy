package br.com.bruno_eduardo_a_soares.my_proxy;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ProxyServer {

    private static final List<String> BLACKLIST_HEADERS = List.of(
            "Connection", "Proxy-Connection", "Keep-Alive", "Transfer-Encoding", "TE", "Trailer", "Upgrade"
    );

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "proxy.port", defaultValue = "3000")
    int proxyPort;

    private NetClient clientHTTPS = null;
    private HttpClient clientHTTP = null;

    void onStart(@Observes final StartupEvent ev) {
        final HttpServer server = this.vertx.createHttpServer();
        this.clientHTTP = this.vertx.createHttpClient(new HttpClientOptions());
        this.clientHTTPS = this.vertx.createNetClient(new NetClientOptions().setTcpNoDelay(true));

        server.requestHandler(request -> {
            this.handleRequest(request);
            log.info("🚀🚀🚀 Sua requisição acabou de entrar no Proxy mais daora da rede, parabéns! 🚀🚀🚀");

        }).listen(this.proxyPort, res -> {
            if (res.succeeded()) {
                log.infof("🚀🚀🚀 Proxy rodando na porta %s%n 🚀🚀🚀", this.proxyPort);
            } else {
                log.error("Falha ao iniciar Proxy", res.cause());
            }
        });
    }

    private void handleRequest(final HttpServerRequest request) {
        if ("CONNECT".equalsIgnoreCase(request.method().name())) {
            log.info("🔒 Conexão HTTPS detectada: " + request.uri());
            this.handleConnectHttps(request);

        } else {
            log.info("🌐 Requisição HTTP detectada: " + request.uri());
            this.handleHttp(request);
        }
    }

    private void handleHttp(final HttpServerRequest requisicaoOriginal) {
        try {
            final URI uri = new URI(requisicaoOriginal.uri());

            final String host = uri.getHost();
            final int port = uri.getPort() == -1 ? 80 : uri.getPort();
            String path = uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }

            this.clientHTTP.request(requisicaoOriginal.method(), port, host, path)
                    .onSuccess(clientRequest -> {

                        requisicaoOriginal.headers().forEach(header -> {
                            if (BLACKLIST_HEADERS.stream().noneMatch(h -> h.equalsIgnoreCase(header.getKey()))) {
                                clientRequest.putHeader(header.getKey(), header.getValue());
                            }
                        });

                        requisicaoOriginal.pipeTo(clientRequest);

                        clientRequest.response().onSuccess(response -> {
                            requisicaoOriginal.response().setStatusCode(response.statusCode());
                            response.headers().forEach(header ->
                                    requisicaoOriginal.response().putHeader(header.getKey(), header.getValue())
                            );
                            response.pipeTo(requisicaoOriginal.response());
                            log.info("✅✅✅ Fim do processamento da requisição HTTP: " + requisicaoOriginal.uri());
                        });

                    })
                    .onFailure(err -> log.error("Erro ao tratar requisição HTTP", err));

        } catch (final Exception e) {
            log.error("Erro ao tratar requisição HTTP", e);
            requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
        }
    }

    private void handleConnectHttps(final HttpServerRequest requisicaoOriginal) {
        try {
            if (this.clientHTTPS == null) {
                log.error("Erro ao tratar requisição HTTPS: NetClient está null.");
                requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                return;
            }

            final String authority = requisicaoOriginal.uri(); // normalmente host:port
            final HostPort hostPort = this.parseAuthority(authority);
            if (hostPort == null) {
                log.error("Erro ao tratar requisição HTTPS: Não foi possível extrair o Host e a Porta.");
                requisicaoOriginal.response().setStatusCode(400).end("Bad CONNECT authority\n");
                return;
            }

            final String destHost = hostPort.host();
            final int destPort = hostPort.port();

            this.clientHTTPS.connect(destPort, destHost, futureServerSocket -> {
                if (futureServerSocket.succeeded()) {
                    // responde 200 e transforma a conexão em raw TCP
                    // obtém o NetSocket do lado cliente (o navegador/cliente HTTP)
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
