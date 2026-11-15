package br.api.universum.gateway;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentProfilesTests {
    @Test
    void productionIsTheDefaultAndDevOverridesItsIssuer() {
        Map.of("", "https://production.coeur.app.br",
                        "prod", "https://production.coeur.app.br",
                        "dev", "https://development.coeur.app.br")
                .forEach((profile, issuer) -> new ApplicationContextRunner()
                        .withPropertyValues(
                                "spring.profiles.active=" + profile,
                                "SERVER_PORT=8080",
                                "IDENTITY_URL=http://antares:8080",
                                "JWT_JWK_SET_URI=http://antares:8080/.well-known/jwks.json",
                                "AUTH_PUBLIC_POST_PATHS=/api/v1/auth/login,/api/v1/auth/refresh,/api/v1/auth/logout",
                                "CORS_ALLOWED_ORIGINS=" + issuer)
                        .withInitializer(new ConfigDataApplicationContextInitializer())
                        .run(context -> {
                            var environment = context.getEnvironment();
                            assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                                    .isEqualTo(issuer);
                            assertThat(environment.getProperty("gateway.services.identity-url"))
                                    .isEqualTo("http://antares:8080");
                            assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"))
                                    .isEqualTo("http://antares:8080/.well-known/jwks.json");
                        }));
    }
}
