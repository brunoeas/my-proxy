package br.com.bruno_eduardo_a_soares.my_proxy;

import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    @Inject
    RequestCacheRedis requestCacheRedis;

    @ConfigProperty(name = "proxy.cache.redis.timeout-consulta-em-segundos", defaultValue = "3")
    int timeoutConsultaRedisEmSegundos;

    private HttpClient clientHTTP = null;
    private MessageDigest algorithmSHA256;

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

            // Recupera o body da requisição original
            requisicaoOriginal.body()
                .onSuccess(bodyBuffer -> {

                    // Monta uma chave identificadora para a requisição
                    final String uriHashSHA256 = this.sha256Hex(requisicaoOriginal.uri().getBytes(StandardCharsets.UTF_8));
                    final String requestKey;
                    if (bodyBuffer.length() > 0) {
                        final String bodyHashSHA256 = this.sha256Hex(bodyBuffer.getBytes());
                        requestKey = String.format("%s+%s", uriHashSHA256, bodyHashSHA256);
                    } else {
                        requestKey = uriHashSHA256;
                    }

                    // Busca no Redis pelo cache da resposta da requisição original
                    final Uni<byte[]> uniCachedRequest = this.requestCacheRedis.get(requestKey);
                    Objects.requireNonNull(uniCachedRequest);
                    uniCachedRequest.map(value -> Optional.ofNullable(value))
                        .ifNoItem().after(Duration.ofSeconds(this.timeoutConsultaRedisEmSegundos)).failWith(new TimeoutException()) // Configura timeout na consulta do Redis
                        .subscribe().with(
                            opt -> opt.ifPresentOrElse(
                                cachedRequest -> { // Aciona esse callback caso tenha encontrado o cache da requisição no Redis
                                    log.infof("✅✅✅ URI: \"%s\" - Retornando resposta cacheada para a requisição original. 🌐 🌐 🌐", requisicaoOriginal.uri());
                                    // Adiciona Header customizado para identificar que a resposta está vindo do cache no Redis
                                    requisicaoOriginal.response().putHeader("X-Cache", "HIT");
                                    // Finaliza a requisição original com 200 OK e envia como resposta o cache obtido do Redis
                                    requisicaoOriginal.response().setStatusCode(200).end(Buffer.buffer(cachedRequest));
                                },
                                () -> { // Aciona esse callback caso NÃO tenha encontrado o cache da requisição no Redis
                                    log.infof("🌐 🌐 🌐 URI: \"%s\" - Preparando para chamar server direto (cache não está presente). 🌐 🌐 🌐", requisicaoOriginal.uri());
                                    this.proxy(requisicaoOriginal, hostPort, bodyBuffer, requestKey);
                                }
                            ),
                            err -> { // Aciona esse callback caso ocorra falha na conexão com Redis
                                log.errorf(
                                    err,
                                    "Erro na conexão com o Redis: Ignorando cacheamento de request e chamando o server direto \"%s\"",
                                    requisicaoOriginal.uri()
                                );
                                this.proxy(requisicaoOriginal, hostPort, bodyBuffer, requestKey);
                            }
                        );
                })
                .onFailure(err -> {
                    log.errorf(
                        err, 
                        "URI \"%s\" - Erro ao tratar requisição HTTP: Erro ao ler body da requisição original",
                        requisicaoOriginal.uri()
                    );
                    requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                });

        } catch (final Exception e) {
            log.errorf(e, "URI \"%s\" - Erro ao tratar requisição HTTP: Erro inesperado", requisicaoOriginal.uri());
            requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
        }
    }

    private void proxy(
        final HttpServerRequest requisicaoOriginal, final HostPort hostPort, final Buffer bodyBuffer, final String requestKey) {

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
                    // Se não existir conexão aberta no pool então faz TCP handshake com o server de destino
                    proxyRequestToServer.send(bodyBuffer)
                            .onSuccess(serverResponse -> {

                                // Copia o Status Code da resposta do server de destino para a resposta que o Cliente/Navegador vai receber
                                requisicaoOriginal.response().setStatusCode(serverResponse.statusCode());
                                // Copia os Header da resposta do server de destino para a resposta que o Cliente/Navegador vai receber
                                serverResponse.headers().forEach(header ->
                                        requisicaoOriginal.response().putHeader(header.getKey(), header.getValue())
                                );
                                // Adiciona Header customizado para identificar que a resposta está vindo do server de destino
                                requisicaoOriginal.response().putHeader("X-Cache", "MISS");

                                // Recupera body de resposta do server de destino
                                serverResponse.body()
                                    .onSuccess(serverResponseBody -> {
                                        if (serverResponseBody.length() > 0) {
                                            // Atualiza o cache de requests no Redis com a resposta do server de destino
                                            this.requestCacheRedis.put(requestKey, serverResponseBody.getBytes())
                                                .subscribe().with(
                                                    _ -> log.infof("URI: \"%s\" - Cache no Redis atualizado.", requisicaoOriginal.uri()),
                                                    erroRedis -> log.warnf(
                                                        erroRedis, "URI: \"%s\" - Erro ao persistir no Redis", requisicaoOriginal.uri()
                                                    )
                                                );
                                        }

                                        // Copia o Body da resposta do server de destino para a resposta que o Cliente/Navegador vai receber
                                        requisicaoOriginal.response().end(serverResponseBody);
                                        log.infof("✅✅✅ URI: \"%s\" - Fim do processamento da requisição HTTP. 🌐 🌐 🌐", requisicaoOriginal.uri());
                                    })
                                    .onFailure(err -> {
                                        log.errorf(
                                            err, 
                                            "URI \"%s\" - Erro ao tratar requisição HTTP: Falha ao obter o body de resposta do"
                                            + " servidor de destino",
                                            requisicaoOriginal.uri()
                                        );
                                        requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                                    });

                            })
                            .onFailure(erro -> {
                                log.errorf(
                                    erro,
                                    "URI \"%s\" - Erro ao tratar requisição HTTP: Erro na resposta do server de destino",
                                    requisicaoOriginal.uri()
                                );
                                requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                            });

                })
                .onFailure(err -> {
                    log.errorf(
                        err,
                        "URI \"%s\" - Erro ao tratar requisição HTTP: Não foi possível preparar a conexão com o server de destino",
                        requisicaoOriginal.uri()
                    );
                    requisicaoOriginal.response().setStatusCode(400).end("Unknown Error\n");
                });
    }

    private boolean notIsHopByHopHeader(final String headerName) {
        return HOP_BY_HOP_HEADERS.stream().noneMatch(h -> h.equalsIgnoreCase(headerName));
    }

    private String sha256Hex(final byte[] data) {
        if (algorithmSHA256 == null) {
            try {
                algorithmSHA256 = MessageDigest.getInstance("SHA-256");
            } catch (final NoSuchAlgorithmException e) {
                throw new RuntimeException(
                    "This exception is thrown when a particular cryptographic algorithm is requested but " +
                    "is not available in the environment."
                );
            }
        }

        final byte[] hash = algorithmSHA256.digest(data);
        return HexFormat.of().formatHex(hash);
    }

}
