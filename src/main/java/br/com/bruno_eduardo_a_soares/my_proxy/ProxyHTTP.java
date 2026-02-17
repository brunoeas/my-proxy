package br.com.bruno_eduardo_a_soares.my_proxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Objects;

/**
 * Forward Proxy HTTP.
 * Para methods HTTP normais reenvia requisições HTTP.
 * - Recebe requisição HTTP
 * - Interpreta
 * - Abre conexão com servidor destino
 * - Faz outra requisição HTTP
 * - Recebe resposta
 * - Repassa ao cliente
 */
@JBossLog
@ApplicationScoped
public class ProxyHTTP {

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "TE", "Trailer", "Transfer-Encoding", "Upgrade"
    );

    @Inject
    Vertx vertx;

    private HttpClient clientHTTP = null;

    @PostConstruct
    public void initProxyHTTP() {
        this.clientHTTP = this.vertx.createHttpClient(new HttpClientOptions());
    }

    public void handleHTTP(final HttpServerRequest requisicaoOriginal, final HostPort hostPort) {
        try {
            Objects.requireNonNull(hostPort);
            if (this.clientHTTP == null) {
                log.error("Erro ao tratar requisição HTTP: Client HTTP do Vertx está null.");
                requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                return;
            }

            // Começa preparando a requisição para o server de destino
            // Neste momento ainda não é estabelecido uma conexão com o server, apenas é preparado um objeto com as
            // configs corretas para fazer a conexão TCP com o server de destino e enviar o HTTP
            this.clientHTTP.request(requisicaoOriginal.method(), hostPort.port(), hostPort.host(), hostPort.path())
                    .onSuccess(proxyRequestToServer -> {

                        // Percorre a lista de headers da requisição original do Cliente/Navegador
                        requisicaoOriginal.headers().forEach(header -> {
                            // Verifica se o Header iterado é valido para ser repassado para a nova requisição
                            if (this.notIsHopByHopHeader(header.getKey())) {
                                // Copia o Header da requisição original para a lista de Headers da nova requisição
                                proxyRequestToServer.putHeader(header.getKey(), header.getValue());
                            }
                        });

                        // Neste momento a conexão com o server de destino é estabelecida e o HTTP é enviado
                        // Se não existir conexão aberta no pool faz TCP handshake com o server de destino
                        proxyRequestToServer.send()
                                .onSuccess(response -> {
                                    // Copia o Status Code da resposta do server de destino para a resposta que o Cliente/Navegador vai receber
                                    requisicaoOriginal.response().setStatusCode(response.statusCode());
                                    // Copia os Header da resposta do server de destino para a resposta que o Cliente/Navegador vai receber
                                    response.headers().forEach(header ->
                                            requisicaoOriginal.response().putHeader(header.getKey(), header.getValue())
                                    );
                                    // Copia o Body da resposta do server de destino para a resposta que o Cliente/Navegador vai receber
                                    response.pipeTo(requisicaoOriginal.response());

                                    log.infof("✅✅✅ URI: \"%s\" - Fim do processamento da requisição HTTP. 🌐 🌐 🌐", requisicaoOriginal.uri());

                                })
                                .onFailure(erro -> {
                                    log.error("Erro ao tratar requisição HTTP: Erro na resposta do server de destino", erro);
                                    requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                                });

                    })
                    .onFailure(err -> {
                        log.error("Erro ao tratar requisição HTTP: Não foi possível preparar a conexão com o server de destino", err);
                        requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                    });

        } catch (final Exception e) {
            log.error("Erro ao tratar requisição HTTP: Erro inesperado", e);
            requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
        }
    }

    private boolean notIsHopByHopHeader(final String headerName) {
        return HOP_BY_HOP_HEADERS.stream().noneMatch(h -> h.equalsIgnoreCase(headerName));
    }

}
