package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class EstimateResultDecoderTest {
    private final EstimateResultDecoder decoder = createDecoder();

    private EstimateResultDecoder createDecoder() {
        var result = new EstimateResultDecoder();
        result.mapper = new ObjectMapper();
        result.initialize();
        return result;
    }

    @Test
    void acceptsCreatedAndFailedExamples() throws Exception {
        var created = decoder.decode(example("estimate-created"));
        assertNotNull(created.estimateId());
        assertNotNull(created.estimateData());
        assertNull(created.failureCode());
        var failed = decoder.decode(example("estimate-creation-failed"));
        assertEquals("PART_NOT_FOUND", failed.failureCode());
        assertEquals("Peça não encontrada", failed.failureMessage());
        assertNull(failed.estimateId());
    }

    @Test
    void rejectsInvalidJsonUnsupportedTypesAndInvalidSchema() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> decoder.decode("{"));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode("null"));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode("{\"type\":\"unknown\"}"));
        String invalid = example("estimate-created").replace("33335831-f3c9-49b7-8f49-3af66b710570", "not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(invalid));
    }

    private String example(String name) throws Exception {
        return Files.readString(Path.of("docs/events/v1", name + ".example.json"));
    }
}
