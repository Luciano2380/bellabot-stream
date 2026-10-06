package com.lpopas.bellabotstream.infrastructure.config.ai;

import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "bellabot.ai.vector-store.chroma")
public record ChromaProperties(
        String baseUrl,
        // Chroma 1.x removeu a API v1 (responde 410 Gone); o default do LangChain4j ainda é V1.
        ChromaApiVersion apiVersion,
        String tenantName,
        String databaseName,
        String collectionName,
        Duration timeout,
        boolean logRequests,
        boolean logResponses
) {}
