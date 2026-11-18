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
        Map<String, Map<String, Object>> profiles = new HashMap<>();
        for (String profile : List.of("dev", "prod")) {
            var resource = new FileSystemResource("examples/config-repo/polaris-" + profile + ".yaml");
            var source = new YamlPropertySourceLoader().load(profile, resource).getFirst();
            Map<String, Object> properties = new HashMap<>();
            ((Map<?, ?>) source.getSource()).keySet()
                    .forEach(key -> properties.put(key.toString(), source.getProperty(key.toString())));
            profiles.put(profile, properties);
        }
        server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            requests.add(request.uri());
            String[] path = request.uri().split("/");
            if (path.length != 3 || !path[1].equals("polaris")) {
                return response.status(404).send();
            }
            String profile = path[2];
            var properties = profiles.get(profile);
            var sources = properties == null ? List.of() : List.of(Map.of(
                    "name", "polaris-" + profile + ".yaml", "source", properties));
            String body = JSONObjectUtils.toJSONString(Map.of(
                    "name", "polaris", "profiles", List.of(profile), "propertySources", sources));
            return response.header("Content-Type", "application/json").sendString(Mono.just(body));
        }).bindNow();
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
