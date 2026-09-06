package com.example.angularrecorder.service;

import com.example.angularrecorder.model.Recording;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class RecordingWriter {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Path write(Path projectRoot, Recording recording) throws IOException {
        Path outputDir = projectRoot
                .resolve("src/test/resources/recordings")
                .resolve(safe(recording.project()))
                .resolve(safe(recording.runId()));

        Files.createDirectories(outputDir);

        Path json = outputDir.resolve("recording.json");
        mapper.writeValue(json.toFile(), recording);

        Path copilot = outputDir.resolve("COPILOT-REQUESTS.md");
        Files.writeString(copilot, copilotRequests(recording), StandardCharsets.UTF_8);

        return outputDir;
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    private String copilotRequests(Recording recording) {
        return """
                # Copilot generation requests

                Recording:
                `%s`

                ## Selenium + Java + Cucumber

                Read `recording.json` in this directory and `.github/copilot-instructions.md`.

                Generate a complete Selenium + Java 17 + Cucumber implementation in this SAME Maven project.

                Requirements:
                - preserve recording order exactly
                - `input` is the primary semantic identity of every control
                - use `identifyBy` candidates according to documented priority
                - support Angular Material/CDK overlays and API-backed search/select
                - support popup/dialog flows and file upload
                - use WebDriverWait; do not use arbitrary Thread.sleep
                - generate feature file, step definitions, page/component objects and runner/configuration
                - update only the existing pom.xml if dependencies are missing
                - do not create another Maven project
                - do not invent actions or assertions

                ## Playwright + Java + Cucumber

                Read `recording.json` in this directory and `.github/copilot-instructions.md`.

                Generate a complete Playwright + Java 17 + Cucumber implementation in this SAME Maven project.

                Requirements:
                - preserve recording order exactly
                - `input` is the primary semantic identity of every control
                - use `identifyBy` candidates according to documented priority
                - support Angular Material/CDK overlays and API-backed search/select
                - support popup/dialog flows and file upload/file chooser
                - use Playwright auto-waiting and semantic locators
                - generate feature file, step definitions, page/component objects and runner/configuration
                - update only the existing pom.xml if dependencies are missing
                - do not create another Maven project
                - do not invent actions or assertions
                """.formatted(
                recording.project() + "/" + recording.runId() + "/recording.json"
        );
    }
}
