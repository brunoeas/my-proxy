package br.com.bruno_eduardo_a_soares.my_proxy;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@JBossLog
@ApplicationScoped
public class ProxyServer {

    @Inject
    Vertx vertx;

    @Inject
    ProxyHTTPS proxyHTTPS;

    @Inject
    ProxyHTTP proxyHTTP;

    @ConfigProperty(name = "proxy.port", defaultValue = "3000")
    int proxyPort;

    void onStart(@Observes final StartupEvent ev) {
        final HttpServer server = this.vertx.createHttpServer();

        server.requestHandler(request -> {
            this.handleRequest(request);
            log.info("🚀🚀🚀 Sua requisição acabou de entrar no Proxy mais daora da rede, parabéns! 🚀🚀🚀");

        }).listen(this.proxyPort, res -> {
            if (res.succeeded()) {
                log.infof("🚀🚀🚀 Proxy rodando na porta %s%n 🚀🚀🚀", this.proxyPort);
            } else {
                log.error("Falha ao iniciar Proxy", res.cause());
                Quarkus.asyncExit();
            }
        });
    }

    private void handleRequest(final HttpServerRequest request) {
        if ("CONNECT".equalsIgnoreCase(request.method().name())) {
            log.info("🔒 Conexão HTTPS detectada: " + request.uri());
            this.proxyHTTPS.handleConnectHttps(request);

        } else {
            log.info("🌐 Requisição HTTP detectada: " + request.uri());
            this.proxyHTTP.handleHttp(request);
        }
    }

}
