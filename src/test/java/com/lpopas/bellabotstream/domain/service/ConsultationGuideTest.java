package com.lpopas.bellabotstream.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConsultationGuideTest {

    private static final String ANALYSIS = """
            Tom de pele: Clara (intensidade suave)
            Subtom: Quente — reflexos dourados
            Tipo de pele: Mista (estimativa) — brilho na testa
            Contraste: Médio — cabelo castanho
            Estação provável: Primavera Quente
            Cores observadas: pele bege-dourado, cabelo castanho, olhos castanhos
            Limitações: nenhuma relevante
            """;

    @Test
    void shouldBuildGuideFromAllFields() {
        assertThat(ConsultationGuide.forAnalysis(ANALYSIS)).containsExactly(
                "- Base e corretivo: fundo amarelado ou dourado",
                "- Cores de blush, batom e sombra (use somente estas): pêssego, coral, dourado e nude quente",
                "- Intensidade da maquiagem: intensidade média",
                "- Cuidados com a pele: hidratante leve no rosto todo e controle de brilho só na zona T (testa, nariz e queixo); protetor solar todos os dias");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Neutro-frio — leve tendência rosada | fundo bege levemente rosado",
            "Neutro-quente — dourado suave      | fundo bege levemente dourado",
            "Frio — avermelhado                  | fundo rosado",
            "Quente (dourado)                    | fundo amarelado ou dourado",
            "Oliva — esverdeado                  | fundo amarelado-esverdeado",
            "Neutro                              | fundo bege neutro"})
    void shouldMapSubtoneToFoundation(String subtone, String expected) {
        assertThat(ConsultationGuide.forAnalysis("Subtom: " + subtone))
                .contains("- Base e corretivo: " + expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Verão Suave     | rosa suave, malva, lavanda e nude rosado",
            "Verao Claro     | rosa suave, malva, lavanda e nude rosado",
            "Outono Escuro   | terracota, cobre, marrom e mostarda",
            "Inverno Escuro  | vermelho, vinho, ameixa e rosa intenso"})
    void shouldMapSeasonToPalette(String season, String expected) {
        assertThat(ConsultationGuide.forAnalysis("Estação provável: " + season))
                .contains("- Cores de blush, batom e sombra (use somente estas): " + expected);
    }

    @Test
    void shouldNotAffirmSkinTypeWhenUndetermined() {
        assertThat(ConsultationGuide.forAnalysis("Tipo de pele: não determinável pela foto — sem sinais"))
                .containsExactly("- Cuidados com a pele: limpeza suave e hidratante leve, sem afirmar o tipo de pele; protetor solar todos os dias");
    }

    @Test
    void shouldReturnEmptyForBlankAnalysis() {
        assertThat(ConsultationGuide.forAnalysis("  ")).isEmpty();
        assertThat(ConsultationGuide.forAnalysis(null)).isEmpty();
    }
}
