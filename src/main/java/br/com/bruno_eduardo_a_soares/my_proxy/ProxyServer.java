package br.com.bruno_eduardo_a_soares.my_proxy;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.List;

@ApplicationScoped
public class ProxyServer {

    private static final List<String> BLACKLIST_HEADERS = List.of("Connection", "Proxy-Connection", "Keep-Alive", "Transfer-Encoding", "TE", "Trailer", "Upgrade");

    @ConfigProperty(name = "proxy.port", defaultValue = "3000")
    int proxyPort;

    void onStart(@Observes StartupEvent ev, Vertx vertx) {

        vertx.createHttpServer()
                .requestHandler(request -> {
                    handleHttp(request, vertx);
                    System.out.println("🚀🚀🚀 Sua requisição acabou de passar pelo Proxy mais daora, parabéns! 🚀🚀🚀");
                })
                .listen(proxyPort, res -> {
                    if (res.succeeded()) {
                        System.out.printf("🚀 Proxy rodando na porta %s%n", proxyPort);
                    } else {
                        res.cause().printStackTrace();
                    }
                });
    }

    private void handleHttp(HttpServerRequest request, Vertx vertx) {
        if ("CONNECT".equalsIgnoreCase(request.method().name())) {
            System.out.println("🔒 Conexão HTTPS detectada: " + request.uri());
            handleConnect(request, vertx);
            return;
        }
        System.out.println("🌐 Requisição HTTP detectada: " + request.uri());

        try {
            URI uri = new URI(request.uri());

            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            String path = uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }

            HttpClient client = vertx.createHttpClient(
                    new HttpClientOptions()
                            .setDefaultHost(host)
                            .setDefaultPort(port)
            );

            client.request(request.method(), port, host, path)
                    .onSuccess(clientRequest -> {

                        request.headers().forEach(header -> {
                            if (BLACKLIST_HEADERS.stream().noneMatch(h -> h.equalsIgnoreCase(header.getKey()))) {
                                clientRequest.putHeader(header.getKey(), header.getValue());
                            }
                        });

                        request.pipeTo(clientRequest);

                        clientRequest.response().onSuccess(response -> {
                            request.response().setStatusCode(response.statusCode());
                            response.headers().forEach(header ->
                                    request.response().putHeader(header.getKey(), header.getValue())
                            );
                            response.pipeTo(request.response());
                            System.out.println("✅✅✅ Requisição HTTP processada com sucesso: " + request.uri());
                        });

                    })
                    .onFailure(err -> err.printStackTrace());

        } catch (Exception e) {
            e.printStackTrace();
            request.response().setStatusCode(400).end("Bad Request");
        }
    }

    private void handleConnect(HttpServerRequest request, Vertx vertx) {
        try {
            String authority = request.uri();
            int idx = authority.lastIndexOf(':');
            String host = authority.substring(0, idx);
            int port = Integer.parseInt(authority.substring(idx + 1));

            vertx.createNetClient().connect(port, host)
                    .onSuccess(serverSocket -> {

                        request.toNetSocket()
                                .onSuccess(clientSocket -> {

                                    clientSocket.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                                            .onSuccess(_ -> {
                                                clientSocket.pipeTo(serverSocket);
                                                serverSocket.pipeTo(clientSocket);
                                                System.out.println("✅✅✅ Requisição HTTPS processada com sucesso (HTTPS Tunnel Established): " + request.uri());
                                            });

                                })
                                .onFailure(err -> err.printStackTrace());

                    })
                    .onFailure(err -> {
                        request.response().setStatusCode(502).end();
                        err.printStackTrace();
                    });

        } catch (Exception e) {
            e.printStackTrace();
            request.response().setStatusCode(400).end("Bad Request");
        }
    }
}
