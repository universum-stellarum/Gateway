package br.api.universum.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=classpath:/application-test.yaml"
})
@ActiveProfiles("test")
class GatewayApplicationTests {
    private static final RSAKey SIGNING_KEY = key();
    private static final AtomicInteger IDENTITY_REQUESTS = new AtomicInteger();
    private static final DisposableServer IDENTITY = upstream("identity", IDENTITY_REQUESTS);

    @Value("${local.server.port}")
    private int port;
    private WebTestClient client;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry properties) {
        properties.add("gateway.services.identity-url", () -> "http://localhost:" + IDENTITY.port());
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + IDENTITY.port() + "/.well-known/jwks.json");
    }

    @BeforeEach
    void client() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void stopUpstreams() {
        IDENTITY.disposeNow();
    }

    @Test
    void loginAndRefreshForwardTheBodyWithoutRequiringAValidAccessToken() {
        for (String endpoint : new String[]{"login", "refresh"}) {
            String body = endpoint.equals("login")
                    ? "{\"email\":\"user@coeur.app\",\"password\":\"secret\"}"
                    : "{\"refreshToken\":\"refresh-value\"}";
            client.post().uri("/api/v1/auth/" + endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer expired-access-token")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.upstream").isEqualTo("identity")
                    .jsonPath("$.body").isEqualTo(body)
                    .jsonPath("$.path").isEqualTo("/api/v1/auth/" + endpoint);
        }
        client.post().uri("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer expired-access-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"refreshToken\":\"refresh-value\"}")
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void protectedRoutesRejectMissingExpiredTamperedAndWrongIssuerTokensBeforeProxying() throws Exception {
        int before = IDENTITY_REQUESTS.get();
        client.get().uri("/api/v1/users/me").exchange().expectStatus().isUnauthorized();
        for (String invalid : new String[]{
                token("https://development.coeur.app.br", Instant.now().minusSeconds(3600), SIGNING_KEY),
                token("https://production.coeur.app.br", Instant.now().plusSeconds(900), SIGNING_KEY),
                token("https://development.coeur.app.br", Instant.now().plusSeconds(900), key()),
                UUID.randomUUID().toString()}) {
            client.get().uri("/api/v1/users/me").headers(headers -> headers.setBearerAuth(invalid))
                    .exchange().expectStatus().isUnauthorized();
        }
        client.post().uri("/api/v1/users/register").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/auth/keys").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/auth/login").exchange().expectStatus().isUnauthorized();
        assertThat(IDENTITY_REQUESTS.get()).isEqualTo(before);
    }

    @Test
    void validJwtIsVerifiedThroughJwksAndForwardedUnchangedToTheCorrectService() throws Exception {
        String accessToken = token("https://development.coeur.app.br", Instant.now().plusSeconds(900), SIGNING_KEY);
        client.get().uri("/api/v1/users/me?include=profile")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.upstream").isEqualTo("identity")
                .jsonPath("$.path").isEqualTo("/api/v1/users/me?include=profile")
                .jsonPath("$.authorization").isEqualTo("Bearer " + accessToken);
        // Business APIs will be added as independent services with their own routes.
        int before = IDENTITY_REQUESTS.get();
        client.get().uri("/api/v1/items").headers(headers -> headers.setBearerAuth(accessToken))
                .exchange().expectStatus().isNotFound();
        assertThat(IDENTITY_REQUESTS.get()).isEqualTo(before);
    }

    @Test
    void corsAllowsConfiguredOriginAndAuthorizationHeaderWithoutDuplicateResponseHeaders() throws Exception {
        client.options().uri("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, "https://development.coeur.app.br")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://development.coeur.app.br");
        client.options().uri("/api/v1/users/me")
                .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange().expectStatus().isForbidden();
        String accessToken = token("https://development.coeur.app.br", Instant.now().plusSeconds(900), SIGNING_KEY);
        client.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .header(HttpHeaders.ORIGIN, "https://development.coeur.app.br")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://development.coeur.app.br");
    }

    @Test
    void publicHealthAndJwksWorkWhileOtherActuatorEndpointsStayBlocked() throws Exception {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.keys[0].kid").isEqualTo(SIGNING_KEY.getKeyID())
                .jsonPath("$.keys[0].d").doesNotExist();
        String accessToken = token("https://development.coeur.app.br", Instant.now().plusSeconds(900), SIGNING_KEY);
        client.get().uri("/actuator/env").headers(headers -> headers.setBearerAuth(accessToken))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void serviceErrorStatusAndBodyArePreserved() throws Exception {
        String accessToken = token("https://development.coeur.app.br", Instant.now().plusSeconds(900), SIGNING_KEY);
        client.get().uri("/api/v1/auth/failure").headers(headers -> headers.setBearerAuth(accessToken))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.detail").isEqualTo("Invalid refresh token.");
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("identity-test-key").generate();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String token(String issuer, Instant expiresAt, RSAKey key) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject(UUID.randomUUID().toString())
                        .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                        .expirationTime(Date.from(expiresAt)).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static DisposableServer upstream(String name, AtomicInteger requests) {
        return HttpServer.create().port(0).handle((request, response) -> {
            if (request.uri().equals("/.well-known/jwks.json")) {
                return response.header("Content-Type", "application/json")
                        .sendString(Mono.just(new JWKSet(SIGNING_KEY.toPublicJWK()).toString()));
            }
            requests.incrementAndGet();
            if (request.uri().equals("/api/v1/auth/logout")) {
                return response.status(204).send();
            }
            if (request.uri().equals("/api/v1/auth/failure")) {
                return response.status(400).header("Content-Type", "application/problem+json")
                        .sendString(Mono.just("{\"detail\":\"Invalid refresh token.\"}"));
            }
            return response.header("Content-Type", "application/json")
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://development.coeur.app.br")
                    .sendString(request.receive().asString().defaultIfEmpty("").map(body ->
                            JSONObjectUtils.toJSONString(Map.of("upstream", name, "path", request.uri(),
                                    "authorization", request.requestHeaders().get(HttpHeaders.AUTHORIZATION, ""),
                                    "body", body))));
        }).bindNow();
    }
}
