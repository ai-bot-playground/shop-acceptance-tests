# Local-first runner: port-forwards the preprod gateway and runs the acceptance
# suite against it. Requires: kubectl context kind-preprod, the stack deployed,
# and Java 25 (the Gradle wrapper downloads Gradle itself).
#
#   ./run-local.ps1                 # all scenarios
#   ./run-local.ps1 -Tags "not @payment-decline"
param(
    [string]$Context = "kind-preprod",
    [int]$Port = 8080,
    [string]$Tags = ""
)
$ErrorActionPreference = "Stop"

Write-Host "Port-forwarding $Context svc/shop-gateway -> localhost:$Port ..."
$pf = Start-Job { kubectl --context $using:Context -n shop port-forward svc/shop-gateway "$($using:Port):8080" }
try {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Seconds 1
        try { $up = (Invoke-WebRequest "http://localhost:$Port/actuator/health" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 }
        catch { $up = $false }
    } until ($up -or (Get-Date) -gt $deadline)
    if (-not $up) { throw "Gateway not reachable on localhost:$Port" }

    $env:SHOP_GATEWAY_URL = "http://localhost:$Port"
    if ($Tags) {
        & .\gradlew.bat test "-Dcucumber.filter.tags=$Tags"
    } else {
        & .\gradlew.bat test
    }
} finally {
    Stop-Job $pf -ErrorAction SilentlyContinue
    Remove-Job $pf -ErrorAction SilentlyContinue
}
