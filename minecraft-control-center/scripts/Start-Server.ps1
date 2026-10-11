param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$ServerDirectory = (Join-Path $PSScriptRoot '..\server'),
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\.jar$')][string]$ServerJar = 'paper.jar',
    [ValidateRange(512, 32768)][int]$MemoryMiB = 2048
)
$ErrorActionPreference = 'Stop'
$serverPath = [IO.Path]::GetFullPath($ServerDirectory)
if (!(Test-Path -LiteralPath (Join-Path $serverPath $ServerJar))) { throw 'The selected server JAR is missing. Prepare the server first.' }
if ((Get-Item -LiteralPath (Join-Path $serverPath $ServerJar)).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Server JAR must be an ordinary file.' }
$eulaPath = Join-Path $serverPath 'eula.txt'
if (!(Test-Path -LiteralPath $eulaPath) -or !(Select-String -LiteralPath $eulaPath -Pattern '^eula=true\s*$' -Quiet)) {
    throw 'Minecraft EULA has not been accepted. Read https://www.minecraft.net/eula before using -AcceptEula with Prepare-Server.ps1.'
}
$javaExecutable = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { 'java.exe' }
if (!(Get-Command $javaExecutable -ErrorAction SilentlyContinue)) { throw 'Java is missing. Install a Java 25 JDK and set JAVA_HOME.' }
$previousErrorAction = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& $javaExecutable -version 2>&1 | Out-String)
    $javaVersionExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = $previousErrorAction }
if ($javaVersionExitCode -ne 0) { throw 'Java version check failed.' }
if ($javaVersion -notmatch 'version "(?<major>\d+)') { throw 'Cannot determine Java version.' }
if ([int]$Matches.major -lt 25) { throw 'Paper 26.3 and MCC Core require Java 25 or newer.' }
Push-Location -LiteralPath $serverPath
try { & $javaExecutable '-Xms512M' "-Xmx${MemoryMiB}M" "-Dmcc.launcher.server-jar=$ServerJar" '-jar' $ServerJar '--nogui' }
finally { Pop-Location }
exit $LASTEXITCODE
