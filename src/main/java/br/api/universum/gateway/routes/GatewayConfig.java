package br.api.universum.gateway.routes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder,
                               @Value("${gateway.services.identity-url}") String identityUrl) {
        return builder.routes()
                .route("identity", route -> route
                        .path("/api/v1/auth/**", "/api/v1/users/**", "/.well-known/jwks.json")
                        .filters(filters -> filters.dedupeResponseHeader(
                                "Access-Control-Allow-Origin Access-Control-Allow-Credentials", "RETAIN_FIRST"))
                        .uri(identityUrl)
                ).build();
    }
}
