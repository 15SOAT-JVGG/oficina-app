package br.com.fiap.postech.soat16.fase1.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

class LocalGatewayIT {
    private static final DockerImageName NGINX = DockerImageName.parse("nginx:1.29-alpine");

    @Test
    void routesCatalogToBillingAndStockToOsForAuthorization() throws Exception {
        try (Network network = Network.newNetwork();
                var app = upstream(network, "app", "os");
                var billing = upstream(network, "billing", "billing");
                var gateway = new GenericContainer<>(NGINX).withNetwork(network).withExposedPorts(8080)
                        .withCopyFileToContainer(MountableFile.forHostPath(Path.of("infra/gateway/nginx.conf")),
                                "/etc/nginx/nginx.conf")) {
            app.start();
            billing.start();
            gateway.start();
            String base = "http://" + gateway.getHost() + ":" + gateway.getMappedPort(8080);
            assertEquals("billing", get(base, "/admin/parts").body());
            assertEquals("billing", get(base, "/admin/parts/example").body());
            assertEquals("billing", get(base, "/admin/services/example").body());
            assertEquals("os", get(base, "/v1/work-orders").body());
            assertEquals("os", get(base, "/admin/parts/example/stock").body());
            assertEquals("os", get(base, "/admin/parts/low-stock").body());
        }
    }

    private GenericContainer<?> upstream(Network network, String alias, String body) {
        return new GenericContainer<>(NGINX).withNetwork(network).withNetworkAliases(alias)
                .withCommand("/bin/sh", "-c", "printf 'server { listen 8080; location / { return 200 \""
                        + body + "\"; } }' > /etc/nginx/conf.d/default.conf; nginx -g 'daemon off;'");
    }

    private HttpResponse<String> get(String base, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
