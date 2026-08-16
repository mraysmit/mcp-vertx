[CmdletBinding()]
param(
  [switch]$KeepRunning,
  [ValidateRange(1024, 65535)]
  [int]$HttpsPort = 18443
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = Split-Path -Parent $PSScriptRoot
$stackDirectory = Join-Path $repository 'integration\oauth'
$composeFile = Join-Path $stackDirectory 'compose.yml'
$runtimeDirectory = Join-Path $repository '.oauth-runtime'
$realmTemplate = Join-Path $stackDirectory 'keycloak\realm-mcp.json'
$runtimeRealm = Join-Path $runtimeDirectory 'realm-mcp.json'
$logDirectory = Join-Path $repository 'logs'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runnerLog = Join-Path $logDirectory "oauth-integration-$timestamp.log"
$serverLog = Join-Path $logDirectory "oauth-integration-server-$timestamp.log"
$serverErrorLog = Join-Path $logDirectory "oauth-integration-server-error-$timestamp.log"
$stackLog = Join-Path $logDirectory "oauth-integration-stack-$timestamp.log"
$rootCertificate = Join-Path $runtimeDirectory 'caddy-data\caddy\pki\authorities\local\root.crt'
$responseFile = Join-Path $runtimeDirectory 'response.json'
$requestFile = Join-Path $runtimeDirectory 'request.json'
$pkceCookieFile = Join-Path $runtimeDirectory 'pkce-cookies.txt'
$pkceLoginFile = Join-Path $runtimeDirectory 'pkce-login.html'
$pkceLoginResultFile = Join-Path $runtimeDirectory 'pkce-login-result.html'
$pkceHeadersFile = Join-Path $runtimeDirectory 'pkce-login-headers.txt'
$pkceFormFile = Join-Path $runtimeDirectory 'pkce-login-form.txt'
$server = $null
$stackStarted = $false
$clientSecret = 'mcp-local-smoke-secret-not-for-production'
$nativeClientId = 'mcp-inspector-native'
$nativeUsername = 'mcp-local-user'
$nativePassword = 'mcp-local-user-not-for-production'
$inspectorVersion = '2.2.0'
$tlsOrigin = "https://localhost:$HttpsPort"
$resourceUri = "$tlsOrigin/mcp"
$savedComposeHttpsPort = [Environment]::GetEnvironmentVariable(
    'MCP_OAUTH_HTTPS_PORT', 'Process')

New-Item -ItemType Directory -Force -Path $logDirectory, $runtimeDirectory | Out-Null
$realm = (Get-Content -Raw -LiteralPath $realmTemplate).Replace(
    'https://localhost:8443/mcp', $resourceUri)
Set-Content -LiteralPath $runtimeRealm -Value $realm -Encoding utf8NoBOM
[Environment]::SetEnvironmentVariable(
    'MCP_OAUTH_HTTPS_PORT', [string]$HttpsPort, 'Process')

function Write-RunnerLog([string]$Message) {
  $line = "$(Get-Date -Format o) $Message"
  Write-Host $line
  Add-Content -LiteralPath $runnerLog -Value $line
}

function Wait-Http([string]$Uri, [int]$Attempts = 120) {
  for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    try {
      $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $Uri
      if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
    } catch {
      Start-Sleep -Milliseconds 500
    }
  }
  throw "Endpoint did not become ready: $Uri"
}

function Invoke-TlsRequest(
    [string]$Method,
    [string]$Path,
    [string[]]$Headers = @(),
    [string]$BodyFile = '') {
  $arguments = @(
    '--silent', '--show-error', '--ssl-no-revoke', '--cacert', $rootCertificate,
    '--request', $Method, '--output', $responseFile,
    '--write-out', '%{http_code}'
  )
  foreach ($header in $Headers) { $arguments += @('--header', $header) }
  if (-not [string]::IsNullOrWhiteSpace($BodyFile)) {
    $arguments += @('--data-binary', "@$BodyFile")
  }
  $arguments += "$tlsOrigin$Path"
  $status = & curl.exe @arguments
  if ($LASTEXITCODE -ne 0) { throw "curl failed for $Method $Path" }
  return [int]$status
}

function Read-JwtClaims([string]$Token) {
  $parts = $Token.Split('.')
  if ($parts.Count -ne 3) { throw 'Authorization server returned a non-JWT access token' }
  $payload = $parts[1].Replace('-', '+').Replace('_', '/')
  switch ($payload.Length % 4) {
    2 { $payload += '==' }
    3 { $payload += '=' }
  }
  return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) |
      ConvertFrom-Json
}

function New-Base64Url([byte[]]$Bytes) {
  return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-RandomBase64Url([int]$ByteCount) {
  $bytes = [byte[]]::new($ByteCount)
  [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  return New-Base64Url $bytes
}

function Invoke-PkceAuthorizationCodeFlow([string]$Issuer) {
  $redirectUri = 'http://127.0.0.1:6276/oauth/callback'
  $state = New-RandomBase64Url 24
  $verifier = New-RandomBase64Url 48
  $challengeBytes = [Security.Cryptography.SHA256]::HashData(
      [Text.Encoding]::ASCII.GetBytes($verifier))
  $challenge = New-Base64Url $challengeBytes
  $query = [Web.HttpUtility]::ParseQueryString('')
  $query['response_type'] = 'code'
  $query['client_id'] = $nativeClientId
  $query['redirect_uri'] = $redirectUri
  $query['scope'] = 'openid mcp:read'
  $query['state'] = $state
  $query['code_challenge'] = $challenge
  $query['code_challenge_method'] = 'S256'
  $authorizationUri = "$Issuer/protocol/openid-connect/auth?$($query.ToString())"

  $loginStatus = & curl.exe --silent --show-error --cookie-jar $pkceCookieFile `
      --output $pkceLoginFile --write-out '%{http_code}' $authorizationUri
  if ($LASTEXITCODE -ne 0 -or [int]$loginStatus -ne 200) {
    throw "Native-client authorization page returned $loginStatus"
  }
  $loginContent = Get-Content -Raw -LiteralPath $pkceLoginFile
  $formMatch = [regex]::Match($loginContent,
      '<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"', 'IgnoreCase')
  if (-not $formMatch.Success) {
    throw 'PKCE red test: Keycloak did not present a login form for the native MCP client'
  }
  $loginAction = [Net.WebUtility]::HtmlDecode($formMatch.Groups[1].Value)
  $formBody = 'username=' + [Uri]::EscapeDataString($nativeUsername) +
      '&password=' + [Uri]::EscapeDataString($nativePassword) + '&credentialId='
  Set-Content -LiteralPath $pkceFormFile -Value $formBody -Encoding ascii -NoNewline
  $loginStatus = & curl.exe --silent --show-error --cookie $pkceCookieFile `
      --cookie-jar $pkceCookieFile --max-redirs 0 --dump-header $pkceHeadersFile `
      --output $pkceLoginResultFile --write-out '%{http_code}' `
      --header 'Content-Type: application/x-www-form-urlencoded' `
      --data-binary "@$pkceFormFile" $loginAction
  if ($LASTEXITCODE -ne 0 -or [int]$loginStatus -notin 302, 303) {
    throw "Native-client login did not redirect to the loopback callback: $loginStatus"
  }
  $locationHeader = Select-String -Path $pkceHeadersFile -Pattern '^Location:\s*(.+)$' |
      Select-Object -Last 1
  if ($null -eq $locationHeader) { throw 'Native-client login omitted the callback Location header' }
  $callback = [Uri]$locationHeader.Matches[0].Groups[1].Value.Trim()
  if ($callback.GetLeftPart([UriPartial]::Path) -ne $redirectUri) {
    throw "Authorization response used an unexpected redirect URI: $callback"
  }
  $callbackQuery = [Web.HttpUtility]::ParseQueryString($callback.Query)
  if ($callbackQuery['state'] -ne $state) { throw 'Authorization response state did not match' }
  if ($callbackQuery['iss'] -ne $Issuer) { throw 'Authorization response issuer did not match RFC 9207' }
  $code = $callbackQuery['code']
  if ([string]::IsNullOrWhiteSpace($code)) { throw 'Authorization response did not contain a code' }

  return Invoke-RestMethod -Method Post -Uri "$Issuer/protocol/openid-connect/token" -Body @{
    grant_type = 'authorization_code'
    client_id = $nativeClientId
    redirect_uri = $redirectUri
    code = $code
    code_verifier = $verifier
  }
}

Push-Location $repository
try {
  Write-RunnerLog 'Building the shaded MCP server JAR'
  & mvn -q -DskipTests package 2>&1 | Tee-Object -FilePath $runnerLog -Append
  if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE" }

  Write-RunnerLog 'Starting pinned Keycloak and Caddy containers'
  $stackStarted = $true
  & docker compose -f $composeFile up -d 2>&1 | Tee-Object -FilePath $runnerLog -Append
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose startup failed with exit code $LASTEXITCODE" }

  Wait-Http 'http://127.0.0.1:8180/realms/mcp/.well-known/openid-configuration'
  Write-RunnerLog 'Keycloak discovery endpoint is ready'

  $savedEnvironment = @{}
  $serverEnvironment = @{
    MCP_HOST = '0.0.0.0'
    MCP_PORT = '3001'
    MCP_HEALTH_ENABLED = 'true'
    MCP_OAUTH_ENABLED = 'true'
    MCP_OAUTH_RESOURCE_URI = $resourceUri
    MCP_OAUTH_ISSUER = 'http://127.0.0.1:8180/realms/mcp'
    MCP_OAUTH_REQUIRED_SCOPES = 'mcp:read'
  }
  foreach ($name in $serverEnvironment.Keys) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $serverEnvironment[$name], 'Process')
  }
  try {
    $java = (Get-Command java -ErrorAction Stop).Source
    $server = Start-Process -FilePath $java -ArgumentList @(
      '-jar', (Join-Path $repository 'target\mcp-vertx-0.3.0-SNAPSHOT.jar')
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $serverLog `
      -RedirectStandardError $serverErrorLog
  } finally {
    foreach ($name in $serverEnvironment.Keys) {
      [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
  }

  Wait-Http 'http://127.0.0.1:3001/.well-known/oauth-protected-resource/mcp'
  Write-RunnerLog 'MCP resource server discovered Keycloak and is ready'

  for ($attempt = 1; $attempt -le 60 -and -not (Test-Path $rootCertificate); $attempt++) {
    Start-Sleep -Milliseconds 250
  }
  if (-not (Test-Path $rootCertificate)) { throw 'Caddy local CA root was not generated' }

  $metadataStatus = Invoke-TlsRequest GET '/.well-known/oauth-protected-resource/mcp'
  if ($metadataStatus -ne 200) { throw "Protected-resource metadata returned $metadataStatus" }
  $metadata = Get-Content -Raw -LiteralPath $responseFile | ConvertFrom-Json
  if ($metadata.resource -ne $resourceUri) {
    throw "Unexpected protected resource identifier: $($metadata.resource)"
  }
  Write-RunnerLog 'TLS and RFC 9728 protected-resource metadata verified'

  $tokenEndpoint = 'http://127.0.0.1:8180/realms/mcp/protocol/openid-connect/token'
  $tokenResponse = Invoke-RestMethod -Method Post -Uri $tokenEndpoint -Body @{
    grant_type = 'client_credentials'
    client_id = 'mcp-local-smoke-client'
    client_secret = $clientSecret
    scope = 'mcp:read'
  }
  $claims = Read-JwtClaims $tokenResponse.access_token
  if ($claims.iss -ne 'http://127.0.0.1:8180/realms/mcp') {
    throw "Unexpected token issuer: $($claims.iss)"
  }
  if (@($claims.aud) -notcontains $resourceUri) {
    throw 'Access token does not contain the canonical MCP resource audience'
  }
  if (($claims.scope -split ' ') -notcontains 'mcp:read') {
    throw 'Access token does not contain the requested mcp:read scope'
  }
  Write-RunnerLog 'Real Keycloak JWT issuer, audience, and scope claims verified'

  $request = @{
    jsonrpc = '2.0'
    id = 1
    method = 'server/discover'
    params = @{
      _meta = @{
        'io.modelcontextprotocol/protocolVersion' = '2026-07-28'
        'io.modelcontextprotocol/clientInfo' = @{ name = 'oauth-smoke'; version = '1.0' }
        'io.modelcontextprotocol/clientCapabilities' = @{}
      }
    }
  } | ConvertTo-Json -Depth 8 -Compress
  Set-Content -LiteralPath $requestFile -Value $request -Encoding utf8NoBOM
  $mcpStatus = Invoke-TlsRequest POST '/mcp' @(
    'Content-Type: application/json',
    'Accept: application/json, text/event-stream',
    'MCP-Protocol-Version: 2026-07-28',
    'Mcp-Method: server/discover',
    "Authorization: Bearer $($tokenResponse.access_token)"
  ) $requestFile
  if ($mcpStatus -ne 200) { throw "Authenticated MCP request returned $mcpStatus" }
  $mcpResponse = Get-Content -Raw -LiteralPath $responseFile | ConvertFrom-Json
  if (@($mcpResponse.result.supportedVersions) -notcontains '2026-07-28') {
    throw 'Authenticated MCP discovery did not advertise the expected protocol version'
  }
  Write-RunnerLog 'Authenticated MCP request succeeded through the TLS reverse proxy'

  $missingStatus = Invoke-TlsRequest GET '/health/live'
  if ($missingStatus -ne 401) { throw "Missing-token request returned $missingStatus instead of 401" }

  $unscopedResponse = Invoke-RestMethod -Method Post -Uri $tokenEndpoint -Body @{
    grant_type = 'client_credentials'
    client_id = 'mcp-local-smoke-client'
    client_secret = $clientSecret
  }
  $scopeStatus = Invoke-TlsRequest GET '/health/live' @(
    "Authorization: Bearer $($unscopedResponse.access_token)"
  )
  if ($scopeStatus -ne 403) { throw "Unscoped token returned $scopeStatus instead of 403" }
  Write-RunnerLog 'Missing-token 401 and insufficient-scope 403 behavior verified'

  $nativeTokens = Invoke-PkceAuthorizationCodeFlow `
      'http://127.0.0.1:8180/realms/mcp'
  $nativeClaims = Read-JwtClaims $nativeTokens.access_token
  if (@($nativeClaims.aud) -notcontains $resourceUri) {
    throw 'PKCE access token does not contain the canonical MCP resource audience'
  }
  if (($nativeClaims.scope -split ' ') -notcontains 'mcp:read') {
    throw 'PKCE access token does not contain mcp:read'
  }
  Write-RunnerLog 'Authorization code, S256 PKCE, state, and RFC 9207 issuer checks passed'

  $savedNodeCa = [Environment]::GetEnvironmentVariable('NODE_EXTRA_CA_CERTS', 'Process')
  [Environment]::SetEnvironmentVariable('NODE_EXTRA_CA_CERTS', $rootCertificate, 'Process')
  try {
    $inspectorOutput = & npx -y "@modelcontextprotocol/inspector@$inspectorVersion" `
        --cli --transport http --server-url $resourceUri `
        --header "Authorization: Bearer $($nativeTokens.access_token)" `
        --method tools/list --format json 2>&1
    $inspectorExit = $LASTEXITCODE
    $inspectorOutput | Tee-Object -FilePath $runnerLog -Append | Write-Host
    if ($inspectorExit -ne 0) {
      throw "Official MCP Inspector CLI failed with exit code $inspectorExit"
    }
  } finally {
    [Environment]::SetEnvironmentVariable('NODE_EXTRA_CA_CERTS', $savedNodeCa, 'Process')
  }
  Write-RunnerLog "Official MCP Inspector $inspectorVersion tools/list succeeded with the PKCE token"
  Write-RunnerLog 'OAuth production-style integration PASS'
} finally {
  if (-not $KeepRunning -and $null -ne $server -and -not $server.HasExited) {
    Stop-Process -Id $server.Id -Force
    $server.WaitForExit()
  }
  if ($stackStarted) {
    & docker compose -f $composeFile logs --no-color 2>&1 |
        Tee-Object -FilePath $stackLog | Out-Null
    if (-not $KeepRunning) {
      & docker compose -f $composeFile down 2>&1 |
          Tee-Object -FilePath $runnerLog -Append | Out-Null
    }
  }
  [Environment]::SetEnvironmentVariable(
      'MCP_OAUTH_HTTPS_PORT', $savedComposeHttpsPort, 'Process')
  Pop-Location
}

Write-Host "Runner log: $runnerLog"
Write-Host "Server log: $serverLog"
Write-Host "Stack log:  $stackLog"
if ($KeepRunning) {
  Write-Host 'Keycloak, Caddy, and the MCP server remain running; stop them manually when finished.'
}
