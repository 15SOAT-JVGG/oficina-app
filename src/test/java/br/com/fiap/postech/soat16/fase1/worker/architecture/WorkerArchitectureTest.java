package br.com.fiap.postech.soat16.fase1.worker.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Dependências da arquitetura de trabalhadores")
class WorkerArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            System.getProperty("user.dir"),
            "src/main/java/br/com/fiap/postech/soat16/fase1");
    private static final Path WORKER_ROOT = SOURCE_ROOT.resolve("worker");

    @Test
    @DisplayName("a aplicação não depende de adaptadores nem de transporte")
    void applicationDependsOnlyOnPortsAndDomain() throws IOException {
        assertNoForbiddenImports(
                WORKER_ROOT.resolve("application"),
                List.of(
                        ".worker.adapter.",
                        ".repository.",
                        ".controller.",
                        ".dto."));
    }

    @Test
    @DisplayName("o domínio não depende da aplicação nem de adaptadores")
    void domainRemainsIndependentFromOuterLayers() throws IOException {
        assertNoForbiddenImports(
                WORKER_ROOT.resolve("domain"),
                List.of(
                        ".worker.application.",
                        ".worker.adapter.",
                        ".repository.",
                        ".controller.",
                        ".dto.",
                        ".service."));
    }

    @Test
    @DisplayName("a consulta de participantes da OS depende da porta de persistência")
    void workOrderCatalogDependsOnWorkerPort() throws IOException {
        Path adapter = SOURCE_ROOT.resolve(
                "workorder/adapter/out/participant/WorkOrderParticipantLookupAdapter.java");
        String source = Files.readString(adapter);

        assertTrue(source.contains(
                "worker.application.port.out.WorkerPersistencePort"));
        assertFalse(source.contains(
                "worker.adapter.out.persistence.WorkerRepository"));
    }

    private void assertNoForbiddenImports(Path sourceRoot, List<String> forbiddenPackages)
            throws IOException {
        List<String> violations;
        try (var files = Files.walk(sourceRoot)) {
            violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> importsFrom(path).stream())
                    .filter(importLine -> forbiddenPackages.stream().anyMatch(importLine::contains))
                    .toList();
        }
        assertTrue(violations.isEmpty(), () -> "Dependências proibidas: " + violations);
    }

    private List<String> importsFrom(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("import "))
                    .map(line -> WORKER_ROOT.relativize(path) + ": " + line)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Falha ao ler " + path, exception);
        }
    }
}
