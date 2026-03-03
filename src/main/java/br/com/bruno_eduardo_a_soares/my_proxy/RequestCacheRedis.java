package br.com.bruno_eduardo_a_soares.my_proxy;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RequestCacheRedis {

    @ConfigProperty(name = "proxy.cache.redis.hash-key", defaultValue = "requests")
    String hashKey;

    private final ReactiveHashCommands<String, String, byte[]> hashCommands;

    @Inject
    public RequestCacheRedis(final ReactiveRedisDataSource redisDataSource) {
        this.hashCommands = redisDataSource.hash(byte[].class);
    }

    public Uni<Boolean> put(final String field, final byte[] value) {
        return hashCommands.hset(this.hashKey, field, value);
    }

    public Uni<byte[]> get(final String field) {
        return hashCommands.hget(this.hashKey, field);
    }

}
