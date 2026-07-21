package com.melon.tools.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArtifactValidatorSelfCheck {

    private ArtifactValidatorSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("artifact-validator");
        Path docx = dir.resolve("report.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(docx))) {
            for (String entry : new String[]{"[Content_Types].xml", "_rels/.rels", "word/document.xml"}) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("<xml/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        if (!ArtifactValidator.validate(docx).valid()) {
            throw new AssertionError("valid DOCX package was rejected");
        }

        Path invalid = dir.resolve("invalid.docx");
        Files.writeString(invalid, "not a zip");
        if (ArtifactValidator.validate(invalid).valid()) {
            throw new AssertionError("invalid DOCX package was accepted");
        }
    }
}
