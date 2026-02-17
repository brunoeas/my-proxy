package br.com.bruno_eduardo_a_soares.my_proxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ProxyHTTP {

    private static final List<String> BLACKLIST_HEADERS = List.of(
            "Connection", "Proxy-Connection", "Keep-Alive", "Transfer-Encoding", "TE", "Trailer", "Upgrade"
    );

    @Inject
    Vertx vertx;

    private HttpClient clientHTTP = null;

    @PostConstruct
    public void initProxyHTTPS() {
        this.clientHTTP = this.vertx.createHttpClient(new HttpClientOptions());
    }

    public void handleHttp(final HttpServerRequest requisicaoOriginal) {
        try {
            if (this.clientHTTP == null) {
                log.error("Erro ao tratar requisição HTTP: Client HTTP está null.");
                requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                return;
            }

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

}
