package dev.mars.mcp;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2Options;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** OAuth 2.0 protected-resource support for the MCP HTTP endpoint. */
final class OAuthResourceServer {
  private static final Logger LOG = LoggerFactory.getLogger(OAuthResourceServer.class);

  enum Status { AUTHENTICATED, MISSING, INVALID, INSUFFICIENT_SCOPE }
  record Authentication(Status status, User user) {}

  record Options(boolean enabled, URI resource, URI issuer, Set<String> requiredScopes,
                 int clockSkewSeconds) {
    static Options from(JsonObject config) {
      boolean enabled = booleanValue(config, "mcp.oauth.enabled", false);
      if (!enabled) return new Options(false, null, null, Set.of(), 30);
      URI resource = absoluteUri(config.getString("mcp.oauth.resourceUri"),
          "mcp.oauth.resourceUri", false);
      URI issuer = absoluteUri(config.getString("mcp.oauth.issuer"),
          "mcp.oauth.issuer", true);
      if (resource.getQuery() != null) {
        throw new IllegalArgumentException("mcp.oauth.resourceUri must not contain a query");
      }
      Set<String> scopes = parseScopes(config.getValue("mcp.oauth.requiredScopes"));
      int skew = config.getInteger("mcp.oauth.clockSkewSeconds", 30);
      if (skew < 0 || skew > 300) {
        throw new IllegalArgumentException("mcp.oauth.clockSkewSeconds must be between 0 and 300");
      }
      return new Options(true, resource, issuer, scopes, skew);
    }

    private static URI absoluteUri(String value, String key, boolean permitLoopbackHttp) {
      if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
      try {
        URI uri = new URI(value);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getFragment() != null
            || uri.getUserInfo() != null) {
          throw new IllegalArgumentException(key + " must be an absolute URI without user-info or fragment");
        }
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost())
            || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme())
            && !(permitLoopbackHttp && loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
          throw new IllegalArgumentException(key + " must use HTTPS"
              + (permitLoopbackHttp ? " (HTTP is allowed only for loopback development issuers)" : ""));
        }
        if (permitLoopbackHttp && uri.getQuery() != null) {
          throw new IllegalArgumentException(key + " must not contain a query");
        }
        return uri;
      } catch (URISyntaxException error) {
        throw new IllegalArgumentException(key + " is not a valid URI", error);
      }
    }

    private static Set<String> parseScopes(Object value) {
      if (value == null) return Set.of();
      List<String> values = new ArrayList<>();
      if (value instanceof JsonArray array) array.forEach(item -> values.add(String.valueOf(item)));
      else for (String item : String.valueOf(value).split("[, ]")) values.add(item);
      values.replaceAll(String::trim);
      values.removeIf(String::isBlank);
      for (String scope : values) {
        if (scope.chars().anyMatch(Character::isWhitespace) || scope.indexOf('"') >= 0
            || scope.indexOf('\\') >= 0) {
          throw new IllegalArgumentException("mcp.oauth.requiredScopes contains an invalid scope");
        }
      }
      return Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static boolean booleanValue(JsonObject config, String key, boolean fallback) {
      Object value = config.getValue(key);
      if (value == null) return fallback;
      if (value instanceof Boolean flag) return flag;
      if (value instanceof String text
          && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
        return Boolean.parseBoolean(text);
      }
      throw new IllegalArgumentException(key + " must be a boolean");
    }
  }

  private final Options options;
  private final OAuth2Auth provider;
  private final String metadataPath;
  private final String metadataUri;

  private OAuthResourceServer(Options options, OAuth2Auth provider) {
    this.options = options;
    this.provider = provider;
    this.metadataPath = metadataPath(options.resource());
    this.metadataUri = origin(options.resource()) + metadataPath;
  }

  static Future<OAuthResourceServer> create(Vertx vertx, Options options) {
    if (!options.enabled()) return Future.succeededFuture(null);
    WebClient client = WebClient.create(vertx);
    URI rfc8414 = wellKnown(options.issuer(), "/.well-known/oauth-authorization-server", true);
    URI oidc = wellKnown(options.issuer(), "/.well-known/openid-configuration", false);
    LOG.info("Discovering OAuth authorization server metadata: issuer={} primary={}",
        options.issuer(), rfc8414);
    return fetchMetadata(client, rfc8414)
        .recover(error -> error instanceof MissingMetadata
            ? fetchMetadata(client, oidc) : Future.failedFuture(error))
        .compose(metadata -> initialize(vertx, options, metadata))
        .eventually(() -> {
          client.close();
          return Future.succeededFuture();
        });
  }

  private static Future<JsonObject> fetchMetadata(WebClient client, URI uri) {
    return client.getAbs(uri.toString()).putHeader("Accept", "application/json").send()
        .compose(response -> parseMetadataResponse(uri, response));
  }

  private static Future<JsonObject> parseMetadataResponse(URI uri, HttpResponse<Buffer> response) {
    if (response.statusCode() == 404) return Future.failedFuture(new MissingMetadata(uri));
    if (response.statusCode() != 200) {
      return Future.failedFuture("OAuth discovery returned HTTP " + response.statusCode() + " from " + uri);
    }
    String type = response.getHeader("Content-Type");
    if (type == null || !type.toLowerCase().startsWith("application/json")) {
      return Future.failedFuture("OAuth discovery did not return application/json from " + uri);
    }
    try {
      return Future.succeededFuture(response.bodyAsJsonObject());
    } catch (RuntimeException error) {
      return Future.failedFuture("OAuth discovery returned invalid JSON from " + uri);
    }
  }

  private static Future<OAuthResourceServer> initialize(
      Vertx vertx, Options options, JsonObject metadata) {
    String discoveredIssuer = metadata.getString("issuer");
    if (!options.issuer().toString().equals(discoveredIssuer)) {
      return Future.failedFuture("OAuth discovery issuer does not match mcp.oauth.issuer");
    }
    URI jwks;
    try {
      boolean loopbackDevelopmentIssuer = "http".equalsIgnoreCase(options.issuer().getScheme());
      jwks = Options.absoluteUri(metadata.getString("jwks_uri"), "discovery jwks_uri",
          loopbackDevelopmentIssuer);
    } catch (IllegalArgumentException error) {
      return Future.failedFuture(error);
    }
    OAuth2Options authOptions = new OAuth2Options()
        .setSite(options.issuer().toString())
        .setJwkPath(jwks.toString())
        .setSupportedGrantTypes(List.of())
        .setJWTOptions(new JWTOptions().setIssuer(options.issuer().toString())
            .addAudience(options.resource().toString()).setLeeway(options.clockSkewSeconds()));
    OAuth2Auth provider;
    try {
      provider = OAuth2Auth.create(vertx, authOptions);
    } catch (RuntimeException error) {
      return Future.failedFuture(error);
    }
    return provider.jWKSet().map(ignored -> {
      LOG.info("OAuth resource server initialized: resource={} issuer={} requiredScopes={}",
          options.resource(), options.issuer(), options.requiredScopes());
      return new OAuthResourceServer(options, provider);
    }).onFailure(error -> provider.close());
  }

  String metadataPath() { return metadataPath; }
  String metadataUri() { return metadataUri; }
  String requiredScopeValue() { return String.join(" ", options.requiredScopes()); }

  void handleMetadata(RoutingContext ctx) {
    JsonObject metadata = new JsonObject()
        .put("resource", options.resource().toString())
        .put("authorization_servers", new JsonArray().add(options.issuer().toString()))
        .put("bearer_methods_supported", new JsonArray().add("header"))
        .put("resource_name", McpServerVerticle.SERVER_NAME);
    if (!options.requiredScopes().isEmpty()) {
      metadata.put("scopes_supported", new JsonArray(new ArrayList<>(options.requiredScopes())));
    }
    ctx.response().putHeader("Content-Type", "application/json; charset=utf-8")
        .putHeader("Cache-Control", "public, max-age=300").end(metadata.encode());
  }

  Future<Authentication> authenticate(String authorization) {
    if (authorization == null || authorization.isBlank()) {
      return Future.succeededFuture(new Authentication(Status.MISSING, null));
    }
    int separator = authorization.indexOf(' ');
    if (separator < 1 || !"Bearer".equalsIgnoreCase(authorization.substring(0, separator))
        || authorization.substring(separator + 1).isBlank()
        || authorization.substring(separator + 1).indexOf(' ') >= 0) {
      return Future.succeededFuture(new Authentication(Status.INVALID, null));
    }
    String token = authorization.substring(separator + 1);
    return provider.authenticate(new TokenCredentials(token)).map(user -> {
      Set<String> granted = tokenScopes(token);
      if (!granted.containsAll(options.requiredScopes())) {
        return new Authentication(Status.INSUFFICIENT_SCOPE, user);
      }
      return new Authentication(Status.AUTHENTICATED, user);
    }).recover(error -> {
      LOG.debug("OAuth access token validation failed", error);
      return Future.succeededFuture(new Authentication(Status.INVALID, null));
    });
  }

  void close() { provider.close(); }

  private static Set<String> tokenScopes(String token) {
    try {
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3) return Set.of();
      JsonObject claims = new JsonObject(new String(Base64.getUrlDecoder().decode(parts[1]),
          StandardCharsets.UTF_8));
      Set<String> scopes = new HashSet<>();
      Object value = claims.getValue("scope");
      if (value instanceof String text) {
        for (String scope : text.split(" ")) if (!scope.isBlank()) scopes.add(scope);
      } else if (value instanceof JsonArray array) {
        array.forEach(scope -> scopes.add(String.valueOf(scope)));
      }
      return scopes;
    } catch (RuntimeException ignored) {
      return Set.of();
    }
  }

  private static String metadataPath(URI resource) {
    String path = resource.getRawPath();
    if (path == null || path.isBlank() || "/".equals(path)) {
      return "/.well-known/oauth-protected-resource";
    }
    return "/.well-known/oauth-protected-resource" + path;
  }

  private static String origin(URI uri) {
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }

  private static URI wellKnown(URI issuer, String suffix, boolean insertBeforePath) {
    try {
      String issuerPath = issuer.getRawPath();
      if (issuerPath == null || "/".equals(issuerPath)) issuerPath = "";
      else if (issuerPath.endsWith("/")) issuerPath = issuerPath.substring(0, issuerPath.length() - 1);
      String path = insertBeforePath ? suffix + issuerPath : issuerPath + suffix;
      return new URI(issuer.getScheme(), issuer.getRawAuthority(), path, null, null);
    } catch (URISyntaxException impossible) {
      throw new IllegalArgumentException(impossible);
    }
  }

  private static final class MissingMetadata extends RuntimeException {
    MissingMetadata(URI uri) { super("OAuth metadata not found at " + uri); }
  }
}
