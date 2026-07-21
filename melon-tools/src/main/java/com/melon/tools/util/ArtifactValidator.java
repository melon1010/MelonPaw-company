package com.melon.tools.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lightweight delivery gate for common generated artifact formats. */
final class ArtifactValidator {

    private static final Map<String, List<String>> OFFICE_REQUIRED_ENTRIES = Map.of(
            "docx", List.of("[Content_Types].xml", "_rels/.rels", "word/document.xml"),
            "pptx", List.of("[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml"),
            "xlsx", List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml"));

    private ArtifactValidator() {
    }

    record Result(boolean valid, String message) {
        static Result success() {
            return new Result(true, "validated");
        }

        static Result invalid(String message) {
            return new Result(false, message);
        }
    }

    static Result validate(Path path) {
        try {
            if (!Files.isRegularFile(path)) return Result.invalid("File does not exist");
            if (Files.size(path) <= 0) return Result.invalid("File is empty");
            String extension = extension(path);
            if (OFFICE_REQUIRED_ENTRIES.containsKey(extension)) {
                return validateOffice(path, OFFICE_REQUIRED_ENTRIES.get(extension));
            }
            return switch (extension) {
                case "pdf" -> validateMagic(path, new byte[]{'%', 'P', 'D', 'F', '-'}, "Invalid PDF header");
                case "png" -> validateMagic(path,
                        new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
                        "Invalid PNG header");
                case "jpg", "jpeg" -> validateMagic(path,
                        new byte[]{(byte) 0xFF, (byte) 0xD8}, "Invalid JPEG header");
                default -> Result.success();
            };
        } catch (IOException e) {
            return Result.invalid("Artifact validation failed: " + e.getMessage());
        }
    }

    private static Result validateOffice(Path path, List<String> requiredEntries) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            for (String required : requiredEntries) {
                ZipEntry entry = zip.getEntry(required);
                if (entry == null || entry.isDirectory()) {
                    return Result.invalid("Invalid Office file: missing " + required);
                }
            }
            return Result.success();
        } catch (IOException e) {
            return Result.invalid("Invalid Office ZIP package: " + e.getMessage());
        }
    }

    private static Result validateMagic(Path path, byte[] expected, String error) throws IOException {
        byte[] actual = new byte[expected.length];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read(actual) != expected.length) return Result.invalid(error);
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) return Result.invalid(error);
        }
        return Result.success();
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
