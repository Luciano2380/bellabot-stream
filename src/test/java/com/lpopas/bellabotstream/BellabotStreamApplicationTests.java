package com.lpopas.bellabotstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Sobe o contexto completo: exige Kafka, Schema Registry, ChromaDB e Ollama de pé, além de
 * TELEGRAM_BOT_TOKEN/TELEGRAM_BOT_USERNAME. Também inicia o long polling, que disputa os updates
 * com o bot se ele estiver rodando. Por isso só roda com BELLABOT_IT=true.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "BELLABOT_IT", matches = "true")
class BellabotStreamApplicationTests {

	@Test
	void contextLoads() {
	}

}
