package br.com.fiap.postech.soat16.fase1.workorder.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

class EventSchemaTest {
    private static final Path EVENTS = Path.of("docs/events/v1");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> EVENT_NAMES = List.of(
            "work-order-opened", "estimate-created", "estimate-creation-failed");

    @Test
    void examplesConformAndInvalidEnvelopesAreRejected() throws Exception {
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for (String name : EVENT_NAMES) {
            JsonNode schemaNode = MAPPER.readTree(EVENTS.resolve(name + ".schema.json").toFile());
            JsonSchema schema = factory.getSchema(schemaNode, config);
            ObjectNode example = (ObjectNode) MAPPER.readTree(EVENTS.resolve(name + ".example.json").toFile());
            assertTrue(schema.validate(example).isEmpty(), name);
            ObjectNode invalid = example.deepCopy();
            invalid.put("eventId", "invalid-uuid");
            assertFalse(schema.validate(invalid).isEmpty(), name);
            invalid = example.deepCopy();
            invalid.remove("workOrderId");
            assertFalse(schema.validate(invalid).isEmpty(), name);
            invalid = example.deepCopy();
            invalid.put("type", "unknown");
            assertFalse(schema.validate(invalid).isEmpty(), name);
        }
    }

    @Test
    void invalidRequestedQuantityIsRejected() throws Exception {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(MAPPER.readTree(EVENTS.resolve("work-order-opened.schema.json").toFile()));
        ObjectNode example = (ObjectNode) MAPPER.readTree(EVENTS.resolve("work-order-opened.example.json").toFile());
        ObjectNode part = (ObjectNode) example.path("data").path("parts").get(0);
        part.put("quantity", 0);
        assertFalse(schema.validate(example).isEmpty());
    }

    @Test
    void openingWithoutItemsIsValid() throws Exception {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(MAPPER.readTree(EVENTS.resolve("work-order-opened.schema.json").toFile()));
        ObjectNode example = (ObjectNode) MAPPER.readTree(EVENTS.resolve("work-order-opened.example.json").toFile());
        ObjectNode data = (ObjectNode) example.get("data");
        data.putArray("services");
        data.putArray("parts");
        assertTrue(schema.validate(example).isEmpty());
    }
}
