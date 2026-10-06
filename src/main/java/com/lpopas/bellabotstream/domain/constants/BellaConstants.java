package com.lpopas.bellabotstream.domain.constants;

public class BellaConstants {

    public static final String TELEGRAM_START = "/start";

    /** Enviada quando a IA falha ou devolve resposta vazia. */
    public static final String AI_FAILURE_MESSAGE =
            "Desculpe, não consegui processar sua mensagem agora. Tente novamente em instantes.";

    public static final String INITIAL_MESSAGE = """
            🤖 Olá Eu sou Bella, seu assistente de IA sobre Maquiagem e Cuidados da Pele
            Envie uma foto do seu rosto!
            Para um resultado perfeito, siga estas dicas antes de mandar a foto:
            🔲 De frente e centralizada
            ☀️ Muita luz: Fique de frente para uma janela ou em um lugar bem iluminado.
            😐 Rosto neutro: Olhe direto para a câmera, sem sorrir e sem óculos ou boné.
            ❌ Sem filtros: Não use filtros, maquiagem pesada ou edições na imagem.
            Mande sua foto agora e receba sua avaliação! 📸
            """;
}
