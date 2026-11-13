# Start AGV backend locally without Nacos (static service discovery).
# Prereqs: MySQL 8 running with database agv_system (see sql/agv_system.sql),
#          JDK 17+ (this script uses D:\Program Files\Java\java21).
#
# Ports: gateway 8080 (front-end entry, /api/**), service-agv 18000.
# Why 18000: something else owns 8000 on this machine, so service-agv is moved.
# The gateway forwards lb://service-agv -> http://localhost:18000 via
# Spring Cloud SimpleDiscoveryClient (spring.cloud.discovery.client.simple.*).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\start-backend.ps1
# Stop with Ctrl+C in each window, or:
#   Get-Process java | Stop-Process
param(
    [string]$Java = 'D:\Program Files\Java\java21\bin\java.exe',
    [string]$MysqlPassword = '123456',
    [int]$ServicePort = 18000,
    [int]$GatewayPort = 8080
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$serviceJar = Join-Path $root 'agv-service\agv\service-agv-1.0-SNAPSHOT.jar'
$gatewayJar = Join-Path $root 'agv\gateway-1.0-SNAPSHOT.jar'

if (-not (Test-Path $Java)) { throw "Java not found: $Java" }
if (-not (Test-Path $serviceJar)) { throw "service jar not found: $serviceJar (run: mvn -DskipTests package)" }
if (-not (Test-Path $gatewayJar)) { throw "gateway jar not found: $gatewayJar (run: mvn -DskipTests package)" }

function Test-Port([int]$port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}
if (Test-Port $GatewayPort) { throw "Port $GatewayPort is already in use" }
if (Test-Port $ServicePort) { throw "Port $ServicePort is already in use" }

$serviceArgs = @(
    '-jar', $serviceJar,
    "--server.port=$ServicePort",
    "--spring.datasource.password=$MysqlPassword",
    '--spring.cloud.nacos.discovery.enabled=false',
    '--spring.cloud.nacos.config.enabled=false',
    "--spring.cloud.discovery.client.simple.instances.service-agv[0].uri=http://localhost:$ServicePort",
    '--spring.cloud.service-registry.auto-registration.enabled=false',
    '--spring.cloud.sentinel.eager=false',
    "--logging.file.name=$logDir\service-agv.log"
)
$gatewayArgs = @(
    '-jar', $gatewayJar,
    "--server.port=$GatewayPort",
    '--spring.cloud.nacos.discovery.enabled=false',
    "--spring.cloud.discovery.client.simple.instances.service-agv[0].uri=http://localhost:$ServicePort",
    '--spring.cloud.service-registry.auto-registration.enabled=false',
    "--logging.file.name=$logDir\gateway.log"
)

Write-Host "Starting service-agv on $ServicePort ..."
$service = Start-Process -FilePath $Java -ArgumentList $serviceArgs -PassThru -WindowStyle Hidden

Write-Host "Waiting for service-agv to become ready ..."
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$ServicePort/agv/list" -Method Post -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
}
if (-not $ready) {
    Write-Host "service-agv did not respond in 60s, check $logDir\service-agv.log" -ForegroundColor Red
    exit 1
}

Write-Host "Starting gateway on $GatewayPort ..."
$gateway = Start-Process -FilePath $Java -ArgumentList $gatewayArgs -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 8
if (-not (Test-Port $GatewayPort)) {
    Write-Host "gateway did not start, check $logDir\gateway.log" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'AGV backend is up:' -ForegroundColor Green
Write-Host "  gateway     http://localhost:$GatewayPort/api/**"
Write-Host "  service-agv http://localhost:$ServicePort"
Write-Host "  pids        service=$($service.Id) gateway=$($gateway.Id)"
Write-Host ''
Write-Host "Smoke test:  curl http://localhost:$GatewayPort/api/map/current"
Write-Host 'Stop:        Get-Process java | Stop-Process'
