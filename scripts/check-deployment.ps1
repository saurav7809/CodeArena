#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$FrontendOrigin,
    [Parameter(Mandatory)][string]$ApiOrigin
)

$ErrorActionPreference = 'Stop'

function Read-HttpsOrigin([string]$Value) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or
        $uri.PathAndQuery -ne '/' -or $uri.Fragment -or $uri.UserInfo) {
        throw 'Supply HTTPS origins without paths, query parameters, or credentials.'
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

$frontend = Read-HttpsOrigin $FrontendOrigin
$api = Read-HttpsOrigin $ApiOrigin

$health = Invoke-RestMethod -Uri "$api/api/health" -TimeoutSec 15
if ($health.status -ne 'UP' -or $health.judgeConfigured -ne $true) {
    throw 'The API did not report database readiness and a configured judge.'
}
$catalog = Invoke-RestMethod -Uri "$api/api/problems?size=1" -TimeoutSec 15
if ($catalog.total -lt 1 -or $catalog.items.Count -lt 1) { throw 'The public problem catalog is empty.' }

$preflightHeaders = @{
    Origin = $frontend
    'Access-Control-Request-Method' = 'POST'
    'Access-Control-Request-Headers' = 'authorization,content-type'
}
$preflight = Invoke-WebRequest -Uri "$api/api/submissions/run" -Method Options -Headers $preflightHeaders -TimeoutSec 15
if (($preflight.Headers['Access-Control-Allow-Origin'] -join ',') -cne $frontend) {
    throw 'The API did not allow the exact frontend origin.'
}
$preflightHeaders.Origin = 'https://unrelated-origin.invalid'
$other = Invoke-WebRequest -Uri "$api/api/submissions/run" -Method Options -Headers $preflightHeaders -TimeoutSec 15 -SkipHttpErrorCheck
if ($other.Headers['Access-Control-Allow-Origin']) { throw 'The API allowed an unrelated origin.' }
$protected = Invoke-WebRequest -Uri "$api/api/users/me" -TimeoutSec 15 -SkipHttpErrorCheck
if ($protected.StatusCode -ne 401) { throw 'The private profile route did not require authentication.' }

$page = Invoke-WebRequest -Uri "$frontend/problems/$($catalog.items[0].slug)" -TimeoutSec 15
$html = [System.Net.WebUtility]::HtmlDecode($page.Content)
if ($html -notmatch '<title>CodeArena' -or -not $html.Contains("connect-src 'self' $api;")) {
    throw 'The frontend deep link did not serve CodeArena configured for this API.'
}
if ($page.Headers['X-Content-Type-Options'] -notcontains 'nosniff') { throw 'The frontend security headers are missing.' }
$assetMatch = [regex]::Match($page.Content, '<script[^>]*\bsrc="(/assets/[^"]+\.js)"')
if (-not $assetMatch.Success) { throw 'The frontend did not reference a built JavaScript asset.' }
$asset = Invoke-WebRequest -Uri ($frontend + $assetMatch.Groups[1].Value) -TimeoutSec 15
if (($asset.Headers['Content-Type'] -join ',') -notmatch 'javascript') {
    throw 'The frontend JavaScript asset was rewritten to an HTML page or served with the wrong content type.'
}

Write-Output 'Deployment checks passed for the API, database, CORS, protected routes, frontend deep links, and JavaScript assets.'
