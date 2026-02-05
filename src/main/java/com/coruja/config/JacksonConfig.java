package com.coruja.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        // Cria um mapper LIMPO para os Controllers REST
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS);

        return objectMapper;
    }
}
