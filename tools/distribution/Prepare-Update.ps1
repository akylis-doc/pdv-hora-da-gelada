param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2100000000)]
    [int]$PreviousVersionCode,
    [string]$OutputDirectory = '',
    [string]$SdkDirectory = '',
    [string]$GradleExecutable = ''
)

$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$GradleExecutable) { $GradleExecutable = Join-Path $projectDirectory 'gradlew.bat' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $projectDirectory 'distribution/build' }
$configurationPath = Join-Path $projectDirectory 'app/google-services.json'
if (!(Test-Path -LiteralPath $configurationPath)) { throw 'Falta app/google-services.json. Use a configuração da loja existente.' }
$configuration = Get-Content -LiteralPath $configurationPath -Raw | ConvertFrom-Json
$firebaseClient = @($configuration.client | Where-Object { $_.client_info.android_client_info.package_name -eq 'br.com.bebidaspdv' })
if ($firebaseClient.Count -ne 1) { throw 'A configuração Firebase deve conter exatamente um cliente br.com.bebidaspdv.' }

if (!$SdkDirectory) {
    $propertiesPath = Join-Path $projectDirectory 'local.properties'
    if (Test-Path -LiteralPath $propertiesPath) {
        $sdkLine = Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
        if ($sdkLine) { $SdkDirectory = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    if (!$SdkDirectory) { $SdkDirectory = $env:ANDROID_HOME }
}
if (!$SdkDirectory) { throw 'Configure o Android SDK no Android Studio ou informe -SdkDirectory.' }
$signerTool = Join-Path $SdkDirectory 'build-tools/35.0.0/apksigner.bat'
if (!(Test-Path -LiteralPath $signerTool)) { throw 'Instale Android SDK Build-Tools 35.0.0 pelo Android Studio.' }

# Builds and verifies locally. Does not log into Firebase, upload, invite, or deploy rules.
& $GradleExecutable -p $projectDirectory :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'A compilação ou os testes falharam. Nenhum pacote de distribuição foi preparado.' }
$apkDirectory = Join-Path $projectDirectory 'app/build/outputs/apk/debug'
$metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'br.com.bebidaspdv' -or @($metadata.elements).Count -ne 1) { throw 'APK inesperado. Confira o identificador e o tipo de build.' }
$element = $metadata.elements[0]
if ([long]$element.versionCode -le $PreviousVersionCode) { throw 'Aumente versionCode em app/build.gradle.kts acima da última versão distribuída.' }
if ($element.versionName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw 'Use versionName no formato 1.0.2.' }
$apk = Join-Path $apkDirectory $element.outputFile
$signatureOutput = & $signerTool verify --print-certs $apk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'A assinatura do APK não passou na verificação.' }
$signatureMatch = [regex]::Match(($signatureOutput -join "`n"), 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})')
$expectedSignature = (Get-Content -LiteralPath (Join-Path $projectDirectory 'distribution/signing-certificate.sha256') -Raw).Trim()
if (!$signatureMatch.Success -or $signatureMatch.Groups[1].Value.ToLowerInvariant() -ne $expectedSignature) { throw 'Assinatura incompatível com o APK anterior. Restaure a chave original.' }
$notes = Join-Path $projectDirectory "distribution/notes-$($element.versionName).txt"
if (!(Test-Path -LiteralPath $notes)) { throw "Escreva as novidades em $notes antes de preparar a distribuição." }

$versionDirectory = Join-Path $OutputDirectory "$($element.versionName)-$($element.versionCode)"
New-Item -ItemType Directory -Force -Path $versionDirectory | Out-Null
$targetApk = Join-Path $versionDirectory "Hora-da-Gelada-PDV-$($element.versionName)-testes.apk"
$apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ((Test-Path -LiteralPath $targetApk) -and (Get-FileHash -LiteralPath $targetApk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $apkHash) {
    throw 'Já existe um APK diferente para esta versão na pasta de saída. Aumente a versão para preservar o histórico.'
}
Copy-Item -LiteralPath $apk -Destination $targetApk
Copy-Item -LiteralPath $notes -Destination (Join-Path $versionDirectory 'novidades.txt')
$result = [ordered]@{
    applicationId = $metadata.applicationId
    versionName = $element.versionName
    versionCode = $element.versionCode
    previousVersionCode = $PreviousVersionCode
    sha256 = $apkHash
    signingCertificateSha256 = $expectedSignature
    firebaseAppId = $firebaseClient[0].client_info.mobilesdk_app_id
    firebaseProjectId = $configuration.project_info.project_id
    distributed = $false
}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $versionDirectory 'verificacao.json') -Encoding UTF8
Write-Output "APK pronto: $targetApk"
Write-Output 'Abra o Firebase App Distribution e envie esse APK. As instruções estão em ATUALIZACOES.md.'
