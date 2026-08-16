package dev.mars.mcp;

import dev.mars.mcp.testing.TestLoggingExtension;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({VertxExtension.class, TestLoggingExtension.class})
class OAuthResourceServerTest {

  @Test
  void validates_security_sensitive_configuration() {
    assertFalse(OAuthResourceServer.Options.from(new JsonObject()).enabled());
    assertThrows(IllegalArgumentException.class, () -> OAuthResourceServer.Options.from(
        new JsonObject().put("mcp.oauth.enabled", true)
            .put("mcp.oauth.resourceUri", "http://mcp.example/mcp")
            .put("mcp.oauth.issuer", "https://auth.example")));
    assertThrows(IllegalArgumentException.class, () -> OAuthResourceServer.Options.from(
        new JsonObject().put("mcp.oauth.enabled", true)
            .put("mcp.oauth.resourceUri", "https://mcp.example/mcp")
            .put("mcp.oauth.issuer", "http://auth.example")));
  }

  @Test
  void falls_back_to_openid_discovery_when_rfc8414_metadata_is_absent(
      Vertx vertx, VertxTestContext context) throws Exception {
    KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    AtomicReference<String> issuer = new AtomicReference<>();
    HttpServer authorizationServer = vertx.createHttpServer().requestHandler(request -> {
      if ("/.well-known/openid-configuration".equals(request.path())) {
        request.response().putHeader("Content-Type", "application/json").end(new JsonObject()
            .put("issuer", issuer.get()).put("jwks_uri", issuer.get() + "/jwks").encode());
      } else if ("/jwks".equals(request.path())) {
        request.response().putHeader("Content-Type", "application/json").end(
            new JsonObject().put("keys", new JsonArray().add(jwk(keys))).encode());
      } else request.response().setStatusCode(404).end();
    });
    authorizationServer.listen(0, "127.0.0.1").compose(server -> {
      issuer.set("http://127.0.0.1:" + server.actualPort());
      McpServerVerticle mcp = new McpServerVerticle(Map.of());
      JsonObject config = new JsonObject().put("mcp.port", 0)
          .put("mcp.oauth.enabled", true)
          .put("mcp.oauth.resourceUri", "https://mcp.example/mcp")
          .put("mcp.oauth.issuer", issuer.get());
      return vertx.deployVerticle(mcp, new DeploymentOptions().setConfig(config)).map(mcp);
    }).onSuccess(mcp -> context.verify(() -> {
      assertTrue(mcp.actualPort() > 0);
      context.completeNow();
    })).onFailure(context::failNow);
  }

  @Test
  void discovers_jwks_publishes_metadata_and_enforces_tokens(
      Vertx vertx, VertxTestContext context) throws Exception {
    KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    AtomicReference<String> issuer = new AtomicReference<>();
    JsonObject jwkSet = new JsonObject().put("keys", new JsonArray().add(jwk(keys)));

    HttpServer authorizationServer = vertx.createHttpServer().requestHandler(request -> {
      if ("/.well-known/oauth-authorization-server".equals(request.path())) {
        request.response().putHeader("Content-Type", "application/json").end(new JsonObject()
            .put("issuer", issuer.get()).put("jwks_uri", issuer.get() + "/jwks").encode());
      } else if ("/jwks".equals(request.path())) {
        request.response().putHeader("Content-Type", "application/json")
            .putHeader("Cache-Control", "public, max-age=300").end(jwkSet.encode());
      } else {
        request.response().setStatusCode(404).end();
      }
    });

    authorizationServer.listen(0, "127.0.0.1")
        .compose(server -> {
          issuer.set("http://127.0.0.1:" + server.actualPort());
          McpServerVerticle mcp = new McpServerVerticle(Map.of());
          JsonObject config = new JsonObject()
              .put("mcp.port", 0)
              .put("mcp.healthEnabled", true)
              .put("mcp.oauth.enabled", true)
              .put("mcp.oauth.resourceUri", "https://mcp.example/mcp")
              .put("mcp.oauth.issuer", issuer.get())
              .put("mcp.oauth.requiredScopes", new JsonArray().add("mcp:read"));
          return vertx.deployVerticle(mcp, new DeploymentOptions().setConfig(config)).map(mcp);
        })
        .compose(mcp -> exerciseResourceServer(vertx, mcp.actualPort(), issuer.get(), keys))
        .onSuccess(ignored -> context.completeNow())
        .onFailure(context::failNow);
  }

  private Future<Void> exerciseResourceServer(
      Vertx vertx, int port, String issuer, KeyPair keys) {
    HttpClient client = vertx.createHttpClient();
    return get(client, port, "/.well-known/oauth-protected-resource/mcp", null)
        .compose(response -> {
          assertEquals(200, response.statusCode());
          return response.body();
        })
        .compose(body -> {
          JsonObject metadata = body.toJsonObject();
          assertEquals("https://mcp.example/mcp", metadata.getString("resource"));
          assertEquals(issuer, metadata.getJsonArray("authorization_servers").getString(0));
          assertEquals("mcp:read", metadata.getJsonArray("scopes_supported").getString(0));
          return get(client, port, "/health/live", null);
        })
        .compose(response -> {
          assertEquals(401, response.statusCode());
          assertTrue(response.getHeader("WWW-Authenticate").contains(
              "resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource/mcp\""));
          return get(client, port, "/health/live", token(issuer,
              "https://mcp.example/mcp", "mcp:read", keys, 300));
        })
        .compose(response -> {
          assertEquals(200, response.statusCode());
          return get(client, port, "/health/live", token(issuer,
              "https://mcp.example/mcp", "other", keys, 300));
        })
        .compose(response -> {
          assertEquals(403, response.statusCode());
          assertTrue(response.getHeader("WWW-Authenticate").contains("error=\"insufficient_scope\""));
          assertTrue(response.getHeader("WWW-Authenticate").contains("scope=\"mcp:read\""));
          return get(client, port, "/health/live", token(issuer,
              "https://wrong.example/mcp", "mcp:read", keys, 300));
        })
        .map(response -> {
          assertEquals(401, response.statusCode());
          assertTrue(response.getHeader("WWW-Authenticate").contains("error=\"invalid_token\""));
          client.close();
          return null;
        });
  }

  private Future<HttpClientResponse> get(HttpClient client, int port, String path, String token) {
    return client.request(HttpMethod.GET, port, "127.0.0.1", path).compose(request -> {
      if (token != null) request.putHeader("Authorization", "Bearer " + token);
      return request.send();
    });
  }

  private static String token(String issuer, String audience, String scope,
                              KeyPair keys, long expiresInSeconds) {
    try {
      JsonObject header = new JsonObject().put("alg", "RS256").put("typ", "JWT")
          .put("kid", "test-key");
      JsonObject claims = new JsonObject().put("iss", issuer).put("aud", audience)
          .put("sub", "integration-test").put("scope", scope)
          .put("iat", Instant.now().getEpochSecond())
          .put("exp", Instant.now().plusSeconds(expiresInSeconds).getEpochSecond());
      String signingInput = encode(header.encode()) + "." + encode(claims.encode());
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(keys.getPrivate());
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
          .encodeToString(signature.sign());
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static JsonObject jwk(KeyPair pair) {
    RSAPublicKey key = (RSAPublicKey) pair.getPublic();
    return new JsonObject().put("kty", "RSA").put("use", "sig").put("alg", "RS256")
        .put("kid", "test-key").put("n", unsigned(key.getModulus()))
        .put("e", unsigned(key.getPublicExponent()));
  }

  private static String unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      bytes = trimmed;
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
