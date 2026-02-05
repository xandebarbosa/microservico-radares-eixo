package com.coruja.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * ✅ CONFIGURAÇÃO OTIMIZADA DE SERIALIZAÇÃO
     */
    public ObjectMapper createRedisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS);

        //Tipagem dinâmica APENAS para o Redis (para serializar Page, etc)
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    /**
     * ✅ CACHE CONFIGURATION COM TTL OTIMIZADO
     * - Dados voláteis: 5-10 minutos
     * - Dados semi-estáveis: 30-60 minutos
     * - Dados estáveis: 2-24 horas
     */
    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        // Cria uma instancia isolada do Mapper para o Redis
        ObjectMapper redisMapper = createRedisObjectMapper();

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(redisMapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer)
                );
    }

    /**
     * ✅ CUSTOMIZAÇÃO POR CACHE
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(RedisCacheConfiguration redisCacheConfiguration) {

        return builder -> {
            Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

            // Cache de buscas de radares (curto - dados mudam rápido)
            cacheConfigs.put("radars-eixo-search",
                    redisCacheConfiguration.entryTtl(Duration.ofMinutes(5)));

            // Cache de busca por placa (médio)
            cacheConfigs.put("radars-eixo-placa",
                    redisCacheConfiguration.entryTtl(Duration.ofMinutes(10)));

            // Cache de filtros/metadata (longo - dados estáveis)
            cacheConfigs.put("opcoes-filtro-eixo",
                    redisCacheConfiguration.entryTtl(Duration.ofHours(2)));

            // Cache de KMs por rodovia (médio)
            cacheConfigs.put("kms-praca-eixo",
                    redisCacheConfiguration.entryTtl(Duration.ofMinutes(30)));

            // Cache do mapa (muito longo - dados raramente mudam)
            cacheConfigs.put("mapa-radares-eixo",
                    redisCacheConfiguration.entryTtl(Duration.ofHours(24)));

            // Cache de localizações (BFF)
            cacheConfigs.put("locais-radares-bff-eixo",
                    redisCacheConfiguration.entryTtl(Duration.ofHours(24)));

            builder.withInitialCacheConfigurations(cacheConfigs);
        };
    }

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisCacheConfiguration cacheConfiguration
    ) {
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration)
                .transactionAware() // ✅ Importante para consistência
                .build();
    }
}
