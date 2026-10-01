param(
    [string[]]$Tasks = @(':app:assembleAppDebug'),
    [string]$JavaHome = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.toolchain\jdk21'),
    [string]$GradleHome = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.toolchain\gradle-9.4.1')
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
Remove-Item Env:DEFAULT_JVM_OPTS -ErrorAction SilentlyContinue
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!(Test-Path -LiteralPath "$JavaHome\bin\java.exe")) { throw "Java 21 is missing at $JavaHome" }
if (!(Test-Path -LiteralPath "$GradleHome\lib\gradle-launcher-9.4.1.jar")) { throw "Gradle 9.4.1 is missing at $GradleHome" }
$vmArgs = @(
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-opens=java.base/java.util=ALL-UNNAMED',
    '--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED',
    '--add-opens=java.base/java.nio.charset=ALL-UNNAMED',
    '--add-opens=java.base/java.net=ALL-UNNAMED',
    '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
    '--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED',
    '--add-opens=java.xml/javax.xml.namespace=ALL-UNNAMED',
    '--add-opens=java.base/java.time=ALL-UNNAMED',
    '-Xmx2048m',
    '-Dfile.encoding=UTF-8',
    "-Duser.home=$env:USERPROFILE"
)
$criteria = Join-Path $projectRoot 'gradle\gradle-daemon-jvm.properties'
$heldCriteria = "$criteria.local-hold"
if (Test-Path -LiteralPath $heldCriteria) { throw 'A previous build is still holding the JVM criteria file' }
$hadCriteria = Test-Path -LiteralPath $criteria
if ($hadCriteria) { Move-Item -LiteralPath $criteria -Destination $heldCriteria }
Push-Location $projectRoot
try {
    # Matching the launcher and build JVM avoids a denied child-process launch on this host.
    & "$JavaHome\bin\java.exe" @vmArgs "-javaagent:$GradleHome\lib\agents\gradle-instrumentation-agent-9.4.1.jar" `
        "-Dorg.gradle.native.dir=$(Join-Path (Split-Path $GradleHome -Parent) 'native')" `
        -classpath "$GradleHome\lib\gradle-launcher-9.4.1.jar" org.gradle.launcher.GradleMain @Tasks `
        --no-daemon --console=plain "-Dorg.gradle.jvmargs=$($vmArgs -join ' ')" "-Porg.gradle.java.installations.paths=$JavaHome" `
        '-Porg.gradle.java.installations.auto-detect=false' '-Porg.gradle.java.installations.auto-download=false'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    if ($hadCriteria) { Move-Item -LiteralPath $heldCriteria -Destination $criteria }
}
