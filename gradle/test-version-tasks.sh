#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p build
fixture="$(mktemp -d "$PWD/build/version-tasks-test.XXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

run_gradle() {
    ./gradlew --offline --no-daemon --console=plain --init-script "$fixture/init.gradle" \
        "-DversionTaskTestDir=$fixture" "$@"
}

cat > "$fixture/init.gradle" <<'EOF'
gradle.beforeProject { project ->
    if (project.path == ':composeApp') {
        def fixtureRoot = new File(System.getProperty('versionTaskTestDir')).canonicalFile
        project.afterEvaluate {
            project.tasks.named('syncIosVersion') {
                appVersion.set('9.9.9-test')
                xcConfigFile.set(new File(fixtureRoot, 'Config.xcconfig'))
            }
            project.tasks.named('generateVersion') {
                appVersion.set('9.9.9-test')
                outputDirectory.set(new File(fixtureRoot, 'generated'))
            }
            project.tasks.matching { it.name in ['cleanGenerateVersion', 'cleanSyncIosVersion'] }.configureEach {
                doFirst { task ->
                    assert task.targetFiles.files.every { it.canonicalPath.startsWith(fixtureRoot.path + File.separator) }
                }
            }
        }
    }
}
EOF

config="$fixture/Config.xcconfig"
source="$fixture/generated/com/maximiliano/maxit/Version.kt"
printf 'APP_VERSION=old-version\nKEEP_SETTING=preserved\n' > "$config"

run_gradle :composeApp:generateVersion --configuration-cache
grep -Fxq 'APP_VERSION=9.9.9-test' "$config" || fail 'iOS version was not replaced'
grep -Fxq 'KEEP_SETTING=preserved' "$config" || fail 'Unrelated iOS settings were lost'
grep -Fq 'const val appVersion = "9.9.9-test"' "$source" || fail 'Kotlin version was not generated'
config_mtime="$(stat -c %y "$config")"
source_mtime="$(stat -c %y "$source")"

run_gradle :composeApp:generateVersion --configuration-cache | tee "$fixture/repeat.log"
grep -Fq 'Configuration cache entry reused.' "$fixture/repeat.log" || fail 'Configuration cache was not reused'
grep -Fq ':composeApp:generateVersion UP-TO-DATE' "$fixture/repeat.log" || fail 'Unchanged Kotlin output was regenerated'
[[ "$(stat -c %y "$config")" == "$config_mtime" ]] || fail 'Matching iOS config was rewritten'
[[ "$(stat -c %y "$source")" == "$source_mtime" ]] || fail 'Matching Kotlin source was rewritten'

printf 'KEEP_SETTING=preserved\n' > "$config"
run_gradle :composeApp:generateVersion --configuration-cache
grep -Fxq 'APP_VERSION=9.9.9-test' "$config" || fail 'Missing iOS version was not appended'
grep -Fxq 'KEEP_SETTING=preserved' "$config" || fail 'Appending the version lost other settings'
config_mtime="$(stat -c %y "$config")"

run_gradle :composeApp:cleanGenerateVersion :composeApp:cleanSyncIosVersion --no-configuration-cache
[[ ! -e "$fixture/generated" ]] || fail 'Generated Kotlin output was not cleaned'
[[ -f "$config" ]] || fail 'Task cleanup deleted the iOS source config'
[[ "$(stat -c %y "$config")" == "$config_mtime" ]] || fail 'Task cleanup changed the iOS source config'

rm -- "$config"
run_gradle :composeApp:generateVersion --configuration-cache
[[ ! -e "$config" ]] || fail 'Missing iOS source config was recreated'
grep -Fq 'const val appVersion = "9.9.9-test"' "$source" || fail 'Cleaned Kotlin output was not regenerated'
printf 'PASS: version sync, incremental generation, cache reuse, and source-safe cleanup\n'
