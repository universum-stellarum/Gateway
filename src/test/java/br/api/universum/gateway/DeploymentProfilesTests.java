package br.api.universum.gateway;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentProfilesTests {
    @Test
    void loadsEachEnvironmentFromConfigServer() throws Exception {
        try (var server = new ConfigServerFixture()) {
            Map.of("prod", "https://production.universum.api.br", "dev", "https://development.universum.api.br")
                    .forEach((profile, issuer) -> runner(profile, server.url()).run(context -> {
                        assertThat(context).hasNotFailed();
                        var environment = context.getEnvironment();
                        assertThat(environment.getActiveProfiles()).containsExactly(profile);
                        assertThat(environment.getProperty("spring.application.name")).isEqualTo("gateway");
                        assertThat(environment.getProperty("server.port")).isEqualTo("8080");
                        assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                                .isEqualTo(issuer);
                        assertThat(environment.getProperty("gateway.routes.identity"))
                                .isEqualTo("http://identity:8080");
                        assertThat(environment.getProperty("universum.services.identity.url"))
                                .isEqualTo(issuer + "/identity");
                        assertThat(environment.getProperty("universum.services.storage.url"))
                                .isEqualTo(issuer + "/storage");
                        assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"))
                                .isEqualTo("http://identity:8080/.well-known/jwks.json");
                        assertThat(environment.getProperty("gateway.cors.allowed-origins")).isEqualTo(issuer);
                        assertThat(environment.getProperty("gateway.security.public-post-paths"))
                                .isEqualTo("/identity/api/v1/auth/login,/identity/api/v1/auth/refresh,/identity/api/v1/auth/logout");
                        assertThat(environment.getProperty("spring.cloud.gateway.server.webflux.httpclient.connect-timeout"))
                                .isEqualTo("3000");
                        assertThat(environment.getProperty("spring.cloud.gateway.server.webflux.httpclient.response-timeout"))
                                .isEqualTo("10s");
                    }));
            assertThat(server.requests()).contains("/gateway/dev", "/gateway/prod");
            assertThat(server.requests()).allMatch(path -> path.startsWith("/gateway/"));
        }
    }

    @Test
    void refusesToStartWhenConfigServerIsUnavailable() throws Exception {
        String url;
        try (var server = new ConfigServerFixture()) {
            url = server.url();
        }
        for (String profile : new String[]{"dev", "prod"}) {
            runner(profile, url).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("Could not locate PropertySource");
            });
        }
    }

    private ApplicationContextRunner runner(String profile, String configServerUrl) {
        return new ApplicationContextRunner()
                .withPropertyValues("APP_ENV=" + profile, "CONFIG_SERVER_URL=" + configServerUrl)
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }
}
