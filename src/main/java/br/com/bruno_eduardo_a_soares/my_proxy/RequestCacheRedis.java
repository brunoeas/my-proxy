package br.com.bruno_eduardo_a_soares.my_proxy;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RequestCacheRedis {

    @ConfigProperty(name = "proxy.cache.redis.expiration-in-seconds", defaultValue = "10")
    long expirationInSeconds;

    private final ReactiveValueCommands<String, byte[]> valueCommands;

    @Inject
    public RequestCacheRedis(final ReactiveRedisDataSource redisDataSource) {
        this.valueCommands = redisDataSource.value(byte[].class);
    }

    public Uni<Void> set(final String field, final byte[] value) {
        return valueCommands.setex(field, this.expirationInSeconds, value);
    }

    public Uni<byte[]> get(final String field) {
        return valueCommands.get(field);
    }

}
