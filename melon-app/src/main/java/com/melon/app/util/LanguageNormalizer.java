package com.melon.app.util;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Maps browser BCP 47 tags to the language bundles shipped by the console. */
public final class LanguageNormalizer {

    public static final String DEFAULT_LANGUAGE = "zh";
    public static final Set<String> SUPPORTED = Set.of("zh", "en", "ja", "ru", "id", "pt-BR", "vi");

    private LanguageNormalizer() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        Locale locale = Locale.forLanguageTag(raw.trim().replace('_', '-'));
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        String normalized = switch (language) {
            case "zh" -> "zh";
            case "en" -> "en";
            case "ja" -> "ja";
            case "ru" -> "ru";
            case "id", "in" -> "id";
            case "pt" -> "pt-BR";
            case "vi" -> "vi";
            default -> "";
        };
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    public static String normalizeOrDefault(String raw) {
        return normalize(raw).orElse(DEFAULT_LANGUAGE);
    }
}
