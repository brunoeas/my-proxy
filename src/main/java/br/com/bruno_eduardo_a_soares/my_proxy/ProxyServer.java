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

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Forward Proxy HTTP.
 * Para methods HTTP normais reenvia requisições HTTP.
 * Para method CONNECT cria um Túnel TCP.
 */
@JBossLog
@ApplicationScoped
public class ProxyServer {

    @Inject
    Vertx vertx;

    @Inject
    ProxyTunelTCP proxyTunelTCP;

    @Inject
    ProxyHTTP proxyHTTP;

    @ConfigProperty(name = "proxy.port", defaultValue = "3000")
    int proxyPort;

    void onStart(@Observes final StartupEvent ev) {
        final HttpServer server = this.vertx.createHttpServer();

        server.requestHandler(request -> {
            this.handleRequest(request);
            log.infof("🚀🚀🚀 URI: \"%s\" - Sua requisição acabou de entrar no Proxy mais daora da rede, parabéns! 🚀🚀🚀", request.uri());

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
        Objects.requireNonNull(request);
        if ("CONNECT".equalsIgnoreCase(request.method().name())) {
            // CONNECT significa que quem chamou o Proxy quer que abra um TÚNEL TCP para trafegar os bytes.
            // Túnel TCP é diferente de conexão TCP! O túnel é quando é feito uma conexão TCP dentro de outra conexão TCP.
            // Por exemplo, quando meu proxy for configurado explicitamente no SO o navegador vai mandar a requisição CONNECT para o meu proxy e
            // Nesse momento que meu proxy for chamado já vai ser aberta uma conexão TCP, e enquanto essa conexão está aberta o meu proxy vai
            // Abrir uma conexão TCP com o server de destino para trafegar os dados HTTP criptografados (HTTPS), se tornando assim um Túnel TCP.
            // Em um contexto em que esse proxy for utlizado em navegadores, receber uma requisição CONNECT significa que é uma requisição HTTPS (ou seja, HTTP com TLS).
            // Mas nada impede de usarem esse proxy para abrir um Túnel TCP para outro protocolo, como por exemplo SSH.
            // Se o cliente mandar para o Proxy os bytes na estrutura HTTP correta com CONNECT, porém apontando para um Host e Porta (tipo 22) e
            // se nesse Host e Porta estiver rodando um servidor SSH então o Cliente passaria a se comunicar diretamente com o server SSH, e o
            // Proxy não saberia que isto é uma conexão SSH pq ele não consegue ver o que está sendo trafegado no Túnel TCP.
            // Como meu proxy é um servidor HTTP criado pelo Vertx ele só vai receber bytes no formato do protocolo HTTP, mas se for um CONNECT
            // A segunda conexão TCP criada pode ser qualquer coisa.
            log.infof("🔒 🔒 🔒 URI: \"%s\" - Requisição via Túnel TCP detectada", request.uri());
            final HostPort hostPort = this.extractHostAndPort(request, ProtocoloEnum.HTTPS);
            this.proxyTunelTCP.openTcpTunnel(request, hostPort);

        } else {
            // HTTP Normal. Forward Proxy HTTP em modo explícito (não túnel).
            // Nesse contexto alguém chamou o meu proxy para trafegar dados HTTP puro, sem Túnel TCP.
            // Aqui o meu Proxy pode interpretar o HTTP, ler method, headers e body e pode modificar essas informações antes de criar a conexão
            // TCP com o server de destino.
            log.infof("🌐 🌐 🌐 URI: \"%s\" - Requisição HTTP detectada", request.uri());
            final HostPort hostPort = this.extractHostAndPort(request, ProtocoloEnum.HTTP);
            this.proxyHTTP.handleHTTP(request, hostPort);
        }
    }

    // Suporte básico para host:port e [ipv6]:port
    private HostPort extractHostAndPort(final HttpServerRequest requisicaoOriginal, final ProtocoloEnum protocolo) {
        Objects.requireNonNull(protocolo);

        final String uriStr = requisicaoOriginal.uri().toLowerCase(Locale.ROOT).startsWith(protocolo.getName())
                ? requisicaoOriginal.uri()
                : protocolo.getName() + requisicaoOriginal.uri();
        final URI uri = URI.create(uriStr);

        final String host = uri.getHost();
        final int port;
        if (ProtocoloEnum.HTTPS.equals(protocolo)) {
            port = uri.getPort() == -1 ? 443 : uri.getPort();
        } else {
            port = uri.getPort() == -1 ? 80 : uri.getPort();
        }
        String path = uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        return new HostPort(host, port, path);
    }

}
