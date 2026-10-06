package com.lpopas.bellabotstream.infrastructure.adapter.out.ai;

import java.util.regex.Pattern;

/**
 * O medgemma devolve o raciocínio entre {@code <unused94>} e {@code <unused95>} antes da resposta final.
 * O raciocínio não pode ser desligado (think:false, instrução no prompt e prefill não funcionam), então
 * é removido em dois pontos: na resposta ao cliente e na memória da conversa.
 */
public final class MedgemmaThinking {

    private static final Pattern THINKING_BLOCK = Pattern.compile("(?s)<unused94>.*?(<unused95>|$)");

    private MedgemmaThinking() {
    }

    /** Se o bloco não for fechado (resposta truncada), tudo a partir da abertura é descartado. */
    public static String remove(String text) {
        return text == null ? "" : THINKING_BLOCK.matcher(text).replaceAll("").strip();
    }
}
