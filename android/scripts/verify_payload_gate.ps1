# Compare the rewritten payload with its original embedded APK, not a different build variant.
param(
    [string]$AppDebug,
    [string]$PayloadApk,
    [string]$SourceApk,
    [string]$PayloadSidecar
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$androidRoot = Split-Path -Parent $PSScriptRoot
if (-not $AppDebug) { $AppDebug = Join-Path $androidRoot "app/build/outputs/apk/debug/app-debug.apk" }
if (-not $PayloadApk) { $PayloadApk = Join-Path $androidRoot "app/build/outputs/embedded/aa-embedded-payload.apk" }
if (-not $SourceApk) { $SourceApk = Join-Path $androidRoot "app/build/outputs/apk/embeddedDebug/app-embeddedDebug.apk" }
if (-not $PayloadSidecar) { $PayloadSidecar = Join-Path $androidRoot "app/build/outputs/embedded/payload.json" }

function Get-ArchiveMembers([string]$Path) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
    $members = [System.Collections.Generic.Dictionary[string,string]]::new([System.StringComparer]::Ordinal)
    try {
        foreach ($entry in $archive.Entries) {
            if ($members.ContainsKey($entry.FullName)) { throw "Duplicate ZIP member: $($entry.FullName)" }
            $stream = $entry.Open()
            $hasher = [System.Security.Cryptography.SHA256]::Create()
            try {
                $hash = [System.BitConverter]::ToString($hasher.ComputeHash($stream)).Replace("-", "").ToLowerInvariant()
                $members.Add($entry.FullName, $hash)
            } finally {
                $hasher.Dispose()
                $stream.Dispose()
            }
        }
    } finally { $archive.Dispose() }
    return ,$members
}
function Test-Native([string]$Name) { return $Name -cmatch '^lib/[^/]+/[^/]+[.]so$' }
function Test-OldSignature([string]$Name) { return $Name -match '^META-INF/(MANIFEST[.]MF|[^/]+[.](SF|RSA|DSA|EC))$' }

$debug = Get-ArchiveMembers $AppDebug
$source = Get-ArchiveMembers $SourceApk
$payload = Get-ArchiveMembers $PayloadApk
$debugNative = @($debug.Keys | Where-Object { Test-Native $_ }).Count
$sourceNative = @($source.Keys | Where-Object { Test-Native $_ }).Count
$payloadNative = @($payload.Keys | Where-Object { Test-Native $_ }).Count
if ($debugNative -le 0) { throw "Standalone APK lost its native libraries (upstream baseline: 28)." }
if ($sourceNative -le 0) { throw "Original embedded APK has no native libraries; expected the unmodified assemble artifact." }
if ($payloadNative -ne 0) { throw "Embedded payload contains $payloadNative native libraries." }
foreach ($name in $payload.Keys) {
    if ($name.StartsWith("lib/", [System.StringComparison]::Ordinal)) { throw "Unexpected payload native-library entry: $name" }
    if (-not $source.ContainsKey($name)) { throw "Unexpected payload member: $name" }
    if ($source[$name] -cne $payload[$name]) { throw "Payload member hash differs from original embedded APK: $name" }
}
$removed = 0
foreach ($name in $source.Keys) {
    if (-not $payload.ContainsKey($name)) {
        if (-not (Test-Native $name) -and -not (Test-OldSignature $name)) { throw "Payload is missing required source member: $name" }
        $removed++
    }
}
if (-not $payload.ContainsKey("resources.arsc")) { throw "Payload has no resources.arsc." }
if (@($payload.Keys | Where-Object { $_ -cmatch '^classes([0-9]+)?[.]dex$' }).Count -eq 0) { throw "Payload has no DEX." }

$sidecar = Get-Content -LiteralPath $PayloadSidecar -Raw | ConvertFrom-Json
$keys = @($sidecar.PSObject.Properties.Name)
$required = @("schemaVersion", "abiVersion", "entry", "sha256")
if ($keys.Count -ne 4) { throw "Sidecar must contain exactly four schema fields." }
foreach ($key in $required) { if ($keys -cnotcontains $key) { throw "Missing sidecar field: $key" } }
if ($sidecar.schemaVersion -isnot [int] -or $sidecar.schemaVersion -ne 1 -or $sidecar.abiVersion -isnot [int] -or $sidecar.abiVersion -ne 1) { throw "Invalid sidecar schema or ABI version." }
if ($sidecar.entry -isnot [string] -or $sidecar.entry -cne "com.agentsanywhere.embedded.PayloadEntry") { throw "Invalid sidecar entry class." }
if ($sidecar.sha256 -isnot [string] -or $sidecar.sha256 -cnotmatch '^[0-9a-f]{64}$') { throw "Invalid sidecar SHA256 format." }
$payloadHash = (Get-FileHash -LiteralPath $PayloadApk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sidecar.sha256 -cne $payloadHash) { throw "Sidecar SHA256 does not match rewritten payload." }
Write-Output "Gate passed: standalone native=$debugNative; source native=$sourceNative; payload native=0; $($payload.Count) source member hashes identical; $removed allowed removals; strict sidecar verified."
