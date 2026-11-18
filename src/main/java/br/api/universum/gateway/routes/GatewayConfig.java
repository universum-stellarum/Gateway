package br.api.universum.gateway.routes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routes(
            RouteLocatorBuilder builder,
            @Value("${gateway.services.identity-url}") String identityUrl,
            @Value("${gateway.services.storage-url}") String storageUrl,
            @Value("${gateway.services.notification-url}") String notificationUrl,
            @Value("${gateway.services.finance-url}") String financeUrl) {
        return builder.routes()
                .route("identity", route -> route
                        .path(
                                "/identity/api/v1/auth/**",
                                "/identity/api/v1/users/**",
                                "/.well-known/jwks.json"
                        )
                        .filters(
                                filters -> filters.dedupeResponseHeader(
                                        "Access-Control-Allow-Origin Access-Control-Allow-Credentials",
                                        "RETAIN_FIRST"
                                )
                        )
                        .uri(identityUrl)
                )
                // Serviços fictícios para estudo: /<serviço>/... vira /... no serviço de destino.
                // Exemplo: GET /storage/files/1 chega em storage como GET /files/1.
                .route("storage", route -> route
                        .path("/storage/**")
                        .filters(filters -> filters.stripPrefix(1))
                        .uri(storageUrl)
                )
                .route("notification", route -> route
                        .path("/notification/**")
                        .filters(filters -> filters.stripPrefix(1))
                        .uri(notificationUrl)
                )
                .route("finance", route -> route
                        .path("/finance/**")
                        .filters(filters -> filters.stripPrefix(1))
                        .uri(financeUrl)
                )
                .build();
    }
}
