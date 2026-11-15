package br.com.fiap.postech.soat16.fase1.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Direção global das dependências hexagonais")
class HexagonalDependencyArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            System.getProperty("user.dir"),
            "src/main/java/br/com/fiap/postech/soat16/fase1");
    private static final List<String> CONTEXTS = List.of(
            "auth",
            "customer",
            "vehicle",
            "worker",
            "workorder");

    @Test
    @DisplayName("aplicações não dependem de adaptadores")
    void applicationsDoNotDependOnAdapters() throws IOException {
        assertNoReferences("application", List.of(".adapter.", ".controller.", ".dto.", ".repository."));
    }

    @Test
    @DisplayName("domínios não dependem de aplicações nem de adaptadores")
    void domainsRemainIndependentFromOuterLayers() throws IOException {
        assertNoReferences(
                "domain",
                List.of(".application.", ".adapter.", ".controller.", ".dto.", ".repository."));
    }

    private void assertNoReferences(String layer, List<String> forbiddenMarkers)
            throws IOException {
        List<String> violations = new ArrayList<>();

        for (String context : CONTEXTS) {
            Path layerRoot = SOURCE_ROOT.resolve(context).resolve(layer);
            if (!Files.exists(layerRoot)) {
                continue;
            }

            try (var files = Files.walk(layerRoot)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> findViolations(path, forbiddenMarkers, violations));
            }
        }

        assertTrue(violations.isEmpty(), () -> "Dependências proibidas: " + violations);
    }

    private void findViolations(
            Path source,
            List<String> forbiddenMarkers,
            List<String> violations
    ) {
        try {
            String content = Files.readString(source);
            forbiddenMarkers.stream()
                    .filter(content::contains)
                    .map(marker -> SOURCE_ROOT.relativize(source) + ": " + marker)
                    .forEach(violations::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Falha ao ler " + source, exception);
        }
    }
}
