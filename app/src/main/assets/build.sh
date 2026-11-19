set -euo pipefail
endpoint="$1"
job="$2"
repository="$3"
revision="$4"
project_path="$5"
task="$6"
java_version="$7"
base="$HOME/.kovka"
work="$base/jobs/$job"
gradle_cache="$base/distributions"
export ANDROID_HOME="$base/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$base/android-user"
export GRADLE_USER_HOME="$base/gradle"
export GIT_TERMINAL_PROMPT=0
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1

if [[ ! -f "$base/ready" ]] || ! command -v curl >/dev/null; then
    echo 'Сначала подготовьте среду на вкладке «Среда».' >&2
    exit 1
fi

mkdir -p "$work/source" "$gradle_cache"
log="$work/build.log"
touch "$log"

post() {
    curl --noproxy '*' --silent --show-error --fail --max-time 15 --data-binary "$2" "$endpoint/$1" >/dev/null
}

publish_log() {
    tail -c 32768 "$log" | post log @-
}

monitor_log() {
    local size previous_size=-1
    while true; do
        size="$(wc -c < "$log")"
        if [[ "$size" != "$previous_size" ]]; then
            publish_log || break
            previous_size="$size"
        fi
        sleep 2
    done
}

finish() {
    status=$?
    trap - EXIT
    kill "$monitor" 2>/dev/null || true
    wait "$monitor" 2>/dev/null || true
    publish_log || true
    if [[ "$status" != 0 ]]; then tail -c 12000 "$log" >&3; fi
    rm -rf "$work"
    exit "$status"
}

monitor_log &
monitor=$!
trap finish EXIT
exec 3>&2
exec >"$log" 2>&1

post phase source
printf 'Исходники %s @ %s\n' "$repository" "$revision"
cd "$work/source"
git init -q
git remote add origin "https://github.com/$repository.git"
git -c credential.helper= fetch -q --depth=1 origin "$revision"
git checkout -q --detach FETCH_HEAD
[[ "$(git rev-parse HEAD)" == "$revision" ]]
git -c credential.helper= submodule update --init --recursive --depth=1

cd -- "$project_path"
case "$(pwd -P)/" in
    "$work/source/"*) ;;
    *) echo 'Папка проекта находится за пределами репозитория.'; exit 1 ;;
esac
wrapper=gradle/wrapper/gradle-wrapper.properties
if [[ ! -f "$wrapper" ]]; then
    echo 'В выбранной папке нет Gradle Wrapper. Укажите папку проекта.'
    exit 1
fi
url="$(sed -n 's/^distributionUrl[[:space:]]*=[[:space:]]*//p' "$wrapper" | tr -d '\r' | sed 's/\\:/:/g')"
if [[ ! "$url" =~ ^https://(services|downloads)\.gradle\.org/distributions/(gradle-([0-9]+)\.([0-9]+)(\.[0-9]+)?)-(bin|all)\.zip$ ]]; then
    echo 'Укажите стабильную версию Gradle из официального Gradle Wrapper.'
    exit 1
fi
distribution="${BASH_REMATCH[2]}"
major="${BASH_REMATCH[3]}"
minor="${BASH_REMATCH[4]}"
if (( major < 7 || (major == 7 && minor < 3) )); then
    echo 'Сборка требует Gradle 7.3 или новее.'
    exit 1
fi
if [[ "$java_version" == auto ]]; then
    java_version=17
    if (( major >= 9 || (major == 8 && minor >= 5) )); then java_version=21; fi
    if (( major > 9 || (major == 9 && minor >= 1) )); then java_version=25; fi
fi
case "$java_version" in
    17) minimum=17.0.21.1 ;;
    21) minimum=21.0.13.1 ;;
    25) minimum=25.0.5.1 ;;
    *) echo 'Неизвестная версия OpenJDK.'; exit 1 ;;
esac
if [[ "$java_version" == 25 ]] && (( major < 9 || (major == 9 && minor == 0) )); then
    echo 'OpenJDK 25 запускает Gradle 9.1 и новее. Выберите «Автоматически» или JDK проекта.'
    exit 1
fi
if [[ "$java_version" == 21 ]] && (( major < 8 || (major == 8 && minor < 5) )); then
    echo 'OpenJDK 21 запускает Gradle 8.5 и новее. Выберите «Автоматически» или JDK 17.'
    exit 1
fi
installed="$(dpkg-query -W -f='${Version}' "openjdk-$java_version")"
if ! dpkg --compare-versions "$installed" ge "$minimum"; then
    echo "Обновите OpenJDK $java_version на вкладке «Среда»."
    exit 1
fi
export JAVA_HOME="$PREFIX/lib/jvm/java-$java_version-openjdk"
export PATH="$JAVA_HOME/bin:$PATH"
printf 'Gradle %s · OpenJDK %s\n' "$distribution" "$installed"

post phase tools
if [[ ! -f "$gradle_cache/$distribution/bin/gradle" ]]; then
    bundle="$distribution-bin.zip"
    download="https://downloads.gradle.org/distributions/$bundle"
    curl -fsSL --retry 2 "$download.sha256" -o "$work/gradle.sha256"
    curl -fSL --retry 2 "$download" -o "$work/$bundle"
    checksum="$(tr -d '[:space:]' < "$work/gradle.sha256")"
    [[ "$checksum" =~ ^[a-fA-F0-9]{64}$ ]]
    (cd "$work" && printf '%s  %s\n' "$checksum" "$bundle" | sha256sum -c -)
    unzip -q "$work/$bundle" -d "$work"
    mv "$work/$distribution" "$gradle_cache/$distribution"
fi
# Явно указываем SDK в рабочей копии, включая проекты с закоммиченным local.properties.
if [[ -e local.properties || -L local.properties ]]; then
    sed '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' local.properties >"$work/local.properties"
    rm local.properties
    mv "$work/local.properties" local.properties
fi
printf '\nsdk.dir=%s\n' "$ANDROID_HOME" >>local.properties

# В историю попадают только APK из этой сборки.
find . -type f -path '*/build/outputs/apk/*' -name '*.apk' -delete
post phase compile
# Отключаем нативные библиотеки Gradle для glibc и используем AAPT2 из Termux.
unset GRADLE_OPTS JAVA_OPTS JDK_JAVA_OPTIONS JAVA_TOOL_OPTIONS
bash "$gradle_cache/$distribution/bin/gradle" "$task" --no-daemon --no-watch-fs \
    --max-workers=2 --console=plain --no-build-cache -Dorg.gradle.native=false "-Dorg.gradle.java.home=$JAVA_HOME" \
    "-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)" \
    -Pandroid.builder.sdkDownload=true -Pkotlin.compiler.execution.strategy=in-process \
    "-Porg.gradle.java.installations.paths=$PREFIX/lib/jvm/java-17-openjdk,$PREFIX/lib/jvm/java-21-openjdk,$PREFIX/lib/jvm/java-25-openjdk" \
    -Porg.gradle.java.installations.auto-detect=false -Porg.gradle.java.installations.auto-download=false

post phase collect
count=0
while IFS= read -r -d '' apk; do
    name="$(printf '%s' "${apk##*/}" | tr -c 'A-Za-z0-9._-' '_' | cut -c 1-96)"
    count=$((count + 1))
    curl --noproxy '*' --silent --show-error --fail --max-time 600 \
        -H 'Expect:' -H "X-Apk-Name: $count-${name%.apk}.apk" \
        --data-binary "@$apk" "$endpoint/artifact" >/dev/null
done < <(find . -type f -path '*/build/outputs/apk/*' -name '*.apk' -print0)
if (( count == 0 )); then
    echo 'Gradle завершился, но в build/outputs/apk нет APK.'
    exit 1
fi
printf 'APK переданы в Ковку: %s\n' "$count"
