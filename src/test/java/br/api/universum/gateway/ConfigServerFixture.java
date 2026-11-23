package br.api.universum.gateway;

import com.nimbusds.jose.util.JSONObjectUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

final class ConfigServerFixture implements AutoCloseable {
    private final Set<String> requests = ConcurrentHashMap.newKeySet();
    private final DisposableServer server;

    ConfigServerFixture() throws IOException {
        // Como o config server real: cada pasta é uma branch do configurations (dev, prod), e a resposta
        // traz primeiro o arquivo do serviço, depois o application compartilhado.
        Map<String, List<Map<String, Object>>> profiles = new HashMap<>();
        for (String profile : List.of("dev", "prod")) {
            profiles.put(profile, List.of(
                    load(profile + "/gateway-" + profile + ".yaml"), load(profile + "/application-" + profile + ".yaml")));
        }
        server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            requests.add(request.uri());
            // /gateway/{perfil}/{label}: cada ambiente tem sua branch, com o mesmo nome do perfil.
            String[] path = request.uri().split("/");
            if (path.length != 4 || !path[1].equals("gateway") || !path[2].equals(path[3])) {
                return response.status(404).send();
            }
            String profile = path[2];
            var sources = profiles.getOrDefault(profile, List.of());
            String body = JSONObjectUtils.toJSONString(Map.of(
                    "name", "gateway", "profiles", List.of(profile), "label", profile,
                    "propertySources", sources));
            return response.header("Content-Type", "application/json").sendString(Mono.just(body));
        }).bindNow();
    }

    private static Map<String, Object> load(String file) throws IOException {
        var resource = new FileSystemResource("examples/config-repo/" + file);
        var source = new YamlPropertySourceLoader().load(file, resource).getFirst();
        Map<String, Object> properties = new HashMap<>();
        ((Map<?, ?>) source.getSource()).keySet()
                .forEach(key -> properties.put(key.toString(), source.getProperty(key.toString())));
        return Map.of("name", file, "source", properties);
    }

    String url() {
        return "http://127.0.0.1:" + server.port();
    }

    Set<String> requests() {
        return Set.copyOf(requests);
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}
