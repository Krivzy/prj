param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$ServerDirectory = (Join-Path $PSScriptRoot '..\server')
)
$ErrorActionPreference = 'Stop'
$serverPath = [IO.Path]::GetFullPath($ServerDirectory)
$pluginJar = Join-Path $serverPath 'plugins\MinecraftControlCenter.jar'
if (!(Test-Path -LiteralPath $pluginJar -PathType Leaf) -or ((Get-Item -LiteralPath $pluginJar).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'The installed MCC JAR is missing or redirected.'
}
$javaExecutable = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { 'java.exe' }
$javaExecutable = (Get-Command $javaExecutable -CommandType Application -ErrorAction Stop).Source
# The client in this JAR verifies the real launcher/child identity and retained lease.
# It requests a normal stop and never forcefully terminates a process.
& $javaExecutable '-Xms32M' '-Xmx96M' '-jar' $pluginJar '--stop' '--server-root' $serverPath
exit $LASTEXITCODE
