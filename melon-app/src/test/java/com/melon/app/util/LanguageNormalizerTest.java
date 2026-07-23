package com.melon.app.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageNormalizerTest {

    @Test
    void mapsBrowserTagsToConsoleBundles() {
        assertEquals("zh", LanguageNormalizer.normalize("zh-CN").orElseThrow());
        assertEquals("pt-BR", LanguageNormalizer.normalize("pt").orElseThrow());
        assertEquals("pt-BR", LanguageNormalizer.normalize("pt-PT").orElseThrow());
        assertEquals("id", LanguageNormalizer.normalize("id-ID").orElseThrow());
        assertEquals("en", LanguageNormalizer.normalize("en_US").orElseThrow());
    }

    @Test
    void rejectsLanguagesWithoutFrontendResources() {
        assertTrue(LanguageNormalizer.normalize("fr-FR").isEmpty());
        assertTrue(LanguageNormalizer.normalize(" ").isEmpty());
    }
}
