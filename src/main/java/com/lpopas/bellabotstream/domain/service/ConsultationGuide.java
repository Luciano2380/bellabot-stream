package com.lpopas.bellabotstream.domain.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Traduz a análise facial (linhas rotuladas definidas em picture-description-system.md) nas orientações
 * da consultoria: fundo da base, paleta da estação, intensidade e cuidados com a pele.
 * <p>
 * Essa tradução é regra fixa e fica no código: nos testes, o modelo de 4B errava a consulta a uma tabela
 * no prompt em 20% a 30% das respostas (cores de outra estação, fundo da base trocado).
 */
public final class ConsultationGuide {

    private ConsultationGuide() {
    }

    /**
     * @param analysis texto da análise facial, com linhas como {@code "Subtom: Quente — ..."}
     * @return orientações, uma por linha começando com {@code "- "}; vazio se nenhum campo for reconhecido
     */
    public static List<String> forAnalysis(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return List.of();
        }
        var guide = new ArrayList<String>();
        field(analysis, "Subtom").flatMap(ConsultationGuide::foundation)
                .ifPresent(value -> guide.add("- Base e corretivo: " + value));
        field(analysis, "Estação provável").flatMap(ConsultationGuide::palette)
                .ifPresent(value -> guide.add("- Cores de blush, batom e sombra (use somente estas): " + value));
        field(analysis, "Contraste").flatMap(ConsultationGuide::intensity)
                .ifPresent(value -> guide.add("- Intensidade da maquiagem: " + value));
        guide.add("- Cuidados com a pele: " + field(analysis, "Tipo de pele").map(ConsultationGuide::skinCare)
                .orElse(UNDETERMINED_SKIN_CARE) + "; protetor solar todos os dias");
        return List.copyOf(guide);
    }

    private static final String UNDETERMINED_SKIN_CARE =
            "limpeza suave e hidratante leve, sem afirmar o tipo de pele";

    /** Valor do campo até o primeiro "—" ou "(", normalizado (minúsculas, sem acentos). */
    static Optional<String> field(String analysis, String label) {
        var matcher = Pattern.compile("(?im)^\\s*" + Pattern.quote(label) + "\\s*:\\s*(.+)$").matcher(analysis);
        if (!matcher.find()) {
            return Optional.empty();
        }
        var value = matcher.group(1).split("[—(]", 2)[0];
        return Optional.of(normalize(value)).filter(v -> !v.isEmpty());
    }

    private static Optional<String> foundation(String subtone) {
        // Os compostos vêm antes: "neutro-frio" também contém "frio".
        if (subtone.contains("neutro-quente") || subtone.contains("neutro quente")) {
            return Optional.of("fundo bege levemente dourado");
        }
        if (subtone.contains("neutro-frio") || subtone.contains("neutro frio")) {
            return Optional.of("fundo bege levemente rosado");
        }
        if (subtone.contains("oliva")) {
            return Optional.of("fundo amarelado-esverdeado");
        }
        if (subtone.contains("quente")) {
            return Optional.of("fundo amarelado ou dourado");
        }
        if (subtone.contains("frio")) {
            return Optional.of("fundo rosado");
        }
        if (subtone.contains("neutro")) {
            return Optional.of("fundo bege neutro");
        }
        return Optional.empty();
    }

    private static Optional<String> palette(String season) {
        if (season.contains("primavera")) {
            return Optional.of("pêssego, coral, dourado e nude quente");
        }
        if (season.contains("verao")) {
            return Optional.of("rosa suave, malva, lavanda e nude rosado");
        }
        if (season.contains("outono")) {
            return Optional.of("terracota, cobre, marrom e mostarda");
        }
        if (season.contains("inverno")) {
            return Optional.of("vermelho, vinho, ameixa e rosa intenso");
        }
        return Optional.empty();
    }

    private static Optional<String> intensity(String contrast) {
        if (contrast.contains("baixo")) {
            return Optional.of("tons suaves e esfumados");
        }
        if (contrast.contains("medio")) {
            return Optional.of("intensidade média");
        }
        if (contrast.contains("alto")) {
            return Optional.of("cores marcantes e delineado definido");
        }
        return Optional.empty();
    }

    private static String skinCare(String skinType) {
        if (skinType.contains("oleosa")) {
            return "limpeza suave duas vezes ao dia, hidratante em gel ou oil-free, base matte e pó na zona T";
        }
        if (skinType.contains("seca")) {
            return "limpeza sem ressecar, hidratante nutritivo e base hidratante ou luminosa";
        }
        if (skinType.contains("mista")) {
            return "hidratante leve no rosto todo e controle de brilho só na zona T (testa, nariz e queixo)";
        }
        if (skinType.contains("normal")) {
            return "rotina simples de limpeza e hidratação";
        }
        return UNDETERMINED_SKIN_CARE;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .strip();
    }
}
