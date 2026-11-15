package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

@ApplicationScoped
public class EstimateResultDecoder {
    @Inject
    ObjectMapper mapper;
    private Map<String, JsonSchema> schemas;

    @PostConstruct
    void initialize() {
        var config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        schemas = Map.of("estimate.created", load(factory, config, "estimate-created"),
                "estimate.creation-failed", load(factory, config, "estimate-creation-failed"));
    }

    public EstimateResultJournal.Event decode(String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Envelope de evento inválido");
            }
            String type = root.path("type").asText();
            JsonSchema schema = schemas.get(type);
            if (schema == null || !schema.validate(root).isEmpty()) {
                throw new IllegalArgumentException("Evento não corresponde ao contrato provisório");
            }
            JsonNode data = root.path("data");
            boolean created = "estimate.created".equals(type);
            return new EstimateResultJournal.Event(uuid(root, "eventId"), uuid(root, "workOrderId"),
                    uuid(root, "causationId"), uuid(root, "sagaId"), uuid(root, "correlationId"),
                    created ? uuid(data, "estimateId") : null, created ? mapper.writeValueAsString(data) : null,
                    created ? null : data.path("code").asText(), created ? null : data.path("message").asText());
        } catch (IOException exception) {
            throw new IllegalArgumentException("JSON de evento inválido", exception);
        }
    }

    private UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }

    private JsonSchema load(JsonSchemaFactory factory, SchemaValidatorsConfig config, String name) {
        try (InputStream input = getClass().getResourceAsStream("/events/v1/" + name + ".schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Schema ausente: " + name);
            }
            return factory.getSchema(input, config);
        } catch (IOException exception) {
            throw new IllegalStateException("Falha ao carregar schema: " + name, exception);
        }
    }
}
