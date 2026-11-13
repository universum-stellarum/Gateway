package br.app.coeur.gateway.routes;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("andromeda", route -> route
                        .path("/api/v1/authentication/**")
                        .uri("http://localhost:8080")
                ).build();
    }
}
