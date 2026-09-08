package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationFilesTest {

    @TempDir
    Path tempDir;

    private final EvaluationFiles files = new EvaluationFiles(new ObjectMapper());

    @Test
    void shouldLoadJsonlRecursivelyAndApplyOptionalDefaults() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Files.writeString(tempDir.resolve("b.jsonl"), caseJson("b"));
        Files.writeString(nested.resolve("a.jsonl"), caseJson("a"));

        List<EvaluationCase> cases = files.loadCases(tempDir);

        assertThat(cases).extracting(EvaluationCase::id).containsExactly("b", "a");
        assertThat(cases.get(0).tags()).isEmpty();
        assertThat(cases.get(0).history()).isEmpty();
        assertThat(cases.get(0).expected().shouldAnswer()).isTrue();
    }

    @Test
    void shouldRejectDuplicateIdsAndUnknownSchemaVersions() throws Exception {
        Files.writeString(tempDir.resolve("duplicate.jsonl"), caseJson("same") + caseJson("same"));
        assertThatThrownBy(() -> files.loadCases(tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same");

        Path unknown = tempDir.resolve("unknown.jsonl");
        Files.writeString(unknown, caseJson("new").replace("\"1.0\"", "\"2.0\""));
        assertThatThrownBy(() -> files.loadCases(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2.0");
    }

    private static String caseJson(String id) {
        return """
                {"schemaVersion":"1.0","id":"%s","category":"FACT","question":"问题",\
                "filters":{},"expected":{"relevantSources":[],"requiredFacts":[],"forbiddenFacts":[]}}
                """.formatted(id);
    }
}
