package com.haiwancodex.www.config;

import com.haiwancodex.www.dto.RedisChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class ChatMemoryConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultMergeable(true);
        return mapper;
    }

    @Bean
    public RedisChatMemory redisChatMemory(StringRedisTemplate redisTemplate) {
        return new RedisChatMemory(redisTemplate);
    }

    @Bean
    public MessageChatMemoryAdvisor chatMemoryAdvisor(RedisChatMemory redisChatMemory) {
        return MessageChatMemoryAdvisor
                .builder(redisChatMemory)
                .build();
    }
}