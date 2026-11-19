#!/bin/bash

toolchains=/kovka/scripts/toolchains.tsv
tools=/kovka/tools
export ANDROID_HOME="$tools/android"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME=/kovka/android-user
export GRADLE_USER_HOME=/kovka/gradle
export CI=true
export PATH="/usr/local/sbin:/usr/sbin:/sbin:$PATH"
export DEBIAN_FRONTEND=noninteractive
export GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export FLUTTER_SUPPRESS_ANALYTICS=true

case "$(uname -m)" in
    aarch64) architecture=arm64 ;;
    x86_64) architecture=x64 ;;
    *) echo 'Неподдерживаемая архитектура.' >&2; exit 1 ;;
esac

prepare_base() {
    if [[ -f "$tools/base-ready" ]]; then return; fi
    mkdir -p "$tools" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME" "$ANDROID_HOME"
    printf 'Устанавливаем инструменты сборки\n'
    dpkg --configure -a
    apt-get -o Acquire::Languages=none -o Acquire::Retries=2 update
    apt-get -o Acquire::Retries=2 install -y --no-install-recommends         ca-certificates curl git python3 unzip xz-utils file zip
    touch "$tools/base-ready"
}

download_checked() {
    local url="$1" archive="$2" hash="$3"
    curl --fail --location --connect-timeout 15 --retry 2 "$url" -o "$archive"
    if [[ "$hash" == sha1:* ]]; then
        printf '%s  %s\n' "${hash#sha1:}" "$archive" | sha1sum -c -
    else
        printf '%s  %s\n' "$hash" "$archive" | sha256sum -c -
    fi
}

get_tool() {
    local name="$1" target="$tools/$1" url hash archive staging
    if [[ -f "$target/.ready" ]]; then return; fi
    read -r url hash < <(awk -F '\t' -v name="$name" -v arch="$architecture"         '$1==name && ($2==arch || $2=="any") {print $3, $4; exit}' "$toolchains") || {
        printf 'Инструмент %s недоступен на этом устройстве.\n' "$name" >&2
        exit 1
    }
    archive="$work/download-$name"
    staging="$work/unpack-$name"
    mkdir -p "$staging"
    printf 'Загружаем %s\n' "$name"
    download_checked "$url" "$archive" "$hash"
    case "$url" in
        *.zip)
            unzip -q "$archive" -d "$staging"
            mv "$staging/cmdline-tools/"* "$staging/"
            rmdir "$staging/cmdline-tools"
            ;;
        */android-sdk-linux-arm64-*-ours.tar.gz) tar -xzf "$archive" -C "$staging" ;;
        *) tar -xf "$archive" --strip-components=1 -C "$staging" ;;
    esac
    rm -f "$archive"
    touch "$staging/.ready"
    rm -rf "$target"
    mv "$staging" "$target"
}

get_flutter() {
    if [[ "$architecture" == x64 ]]; then
        get_tool flutter
        return
    fi
    if [[ -f "$tools/flutter/.ready" ]]; then return; fi
    local url revision stable_version staging="$work/flutter"
    read -r url revision < <(awk -F '\t' '$1=="flutter-git" && $2=="arm64" {print $3, $4; exit}' "$toolchains")
    stable_version="$(awk -F '\t' '$1=="flutter" && $2=="x64" {print $3;exit}' "$toolchains" | sed -E 's@.*/flutter_linux_([0-9.]+)-stable.*@\1@')"
    [[ "$stable_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
    printf 'Загружаем Flutter\n'
    git clone -q --depth=1 --branch "$stable_version" "$url" "$staging"
    git -C "$staging" checkout -q -B stable
    [[ "$(git -C "$staging" rev-parse HEAD)" == "$revision" ]]
    touch "$staging/.ready"
    rm -rf "$tools/flutter"
    mv "$staging" "$tools/flutter"
}

java_for_gradle() {
    local major="$1" minor="$2"
    version=17
    if (( major > 8 || (major == 8 && minor >= 5) )); then version=21; fi
    if (( major > 9 || (major == 9 && minor >= 1) )); then version=25; fi
    if (( major > 9 || (major == 9 && minor >= 8) )); then version=27; fi
    if [[ "$requested_java" != auto ]]; then version="$requested_java"; fi
    get_tool "jdk-$version"
    export JAVA_HOME="$tools/jdk-$version"
    export PATH="$JAVA_HOME/bin:$PATH"
    "$JAVA_HOME/bin/java" -version
}

read_wrapper() {
    local wrapper="$1/gradle/wrapper/gradle-wrapper.properties"
    local distribution_url
    [[ -f "$wrapper" ]] || { echo 'В этой папке нет Gradle Wrapper.'; exit 1; }
    distribution_url="$(sed -n 's/^distributionUrl[[:space:]]*=[[:space:]]*//p' "$wrapper" | tr -d '\r' | sed 's/\\:/:/g')"
    if [[ ! "$distribution_url" =~ ^https://(services|downloads)\.gradle\.org/distributions/(gradle-([0-9]+)\.([0-9]+)(\.[0-9]+)?)-(bin|all)\.zip$ ]]; then
        echo 'Для сборки нужна стабильная версия Gradle с официального сервера.' >&2
        exit 1
    fi
    gradle_version="${BASH_REMATCH[2]}"
    gradle_major="${BASH_REMATCH[3]}"
    gradle_minor="${BASH_REMATCH[4]}"
    if (( gradle_major < 7 || (gradle_major == 7 && gradle_minor < 3) )); then
        echo 'Для сборки нужен Gradle 7.3 или новее.'
        exit 1
    fi
}

prepare_android() {
    mkdir -p "$GRADLE_USER_HOME"
    if [[ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ]]; then
        get_tool sdk-cli
        printf 'Готовим Android SDK\n'
        set +o pipefail
        yes | "$tools/sdk-cli/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses > "$work/sdk-licenses.log"
        set -o pipefail
    fi
    if [[ "$architecture" == arm64 ]]; then
        get_tool arm-sdk-tools
        local aapt2
        aapt2="$(find "$tools/arm-sdk-tools" -type f -name aapt2 -print -quit)"
        [[ -x "$aapt2" ]] || { echo 'AAPT2 недоступен в среде сборки.'; exit 1; }
        printf 'android.aapt2FromMavenOverride=%s\n' "$aapt2" > "$GRADLE_USER_HOME/gradle.properties"
    fi
}

prepare_flutter_android() {
    get_tool sdk-cli
    if [[ ! -f "$ANDROID_HOME/platforms/android-36/android.jar" || ! -d "$ANDROID_HOME/platform-tools" ]]; then
        "$tools/sdk-cli/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
            "platform-tools" "platforms;android-36" "build-tools;36.0.0"
    fi
}

prepare_arm_ndk() {
    [[ "$architecture" == arm64 ]] || return 0
    local version
    version="$(grep -hE 'ndkVersion|^[[:space:]]*ndk[[:space:]]*=' \
        "$project/android/build.gradle" "$project/android/build.gradle.kts" \
        "$project/node_modules/react-native/ReactAndroid/gradle/libs.versions.toml" 2>/dev/null \
        | grep -oE '[0-9]{2}\.[0-9]+\.[0-9]{7,}' | head -1 || true)"
    [[ -n "$version" ]] || return 0
    case "$version" in
        27.1.12297006|28.2.13676358) ;;
        *) printf 'Для этого проекта требуется NDK %s. Совместимая сборка для телефона пока отсутствует.\n' "$version"; exit 1 ;;
    esac
    get_tool "ndk-$version"
    mkdir -p "$ANDROID_HOME/ndk"
    if [[ ! -e "$ANDROID_HOME/ndk/$version" ]]; then
        ln -s "$tools/ndk-$version" "$ANDROID_HOME/ndk/$version"
    fi
}

sign_apk() {
    local package="$1" output="$2" signer key="$ANDROID_USER_HOME/debug.keystore"
    signer="$(find "$ANDROID_HOME/build-tools" -name apksigner.jar -type f | sort -V | tail -1)"
    [[ -f "$signer" ]] || { echo 'Не найден инструмент подписи APK.' >&2; exit 1; }
    if [[ ! -f "$key" ]]; then
        "$JAVA_HOME/bin/keytool" -genkeypair -alias androiddebugkey -keyalg RSA -keysize 2048 \
            -validity 10000 -keystore "$key" -storepass android -keypass android -dname 'CN=Android Debug'
    fi
    "$JAVA_HOME/bin/java" -jar "$signer" sign --v4-signing-enabled false --ks "$key" \
        --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output" "$package"
}

gradle_build() {
    local task="$1" distribution_dir="$tools/distributions/$gradle_version"
    if [[ ! -x "$distribution_dir/bin/gradle" ]]; then
        local archive="$work/$gradle_version-bin.zip"
        local url="https://downloads.gradle.org/distributions/$gradle_version-bin.zip"
        local digest
        digest="$(curl -fsSL --connect-timeout 15 --retry 2 "$url.sha256" | tr -d '[:space:]')"
        [[ "$digest" =~ ^[a-fA-F0-9]{64}$ ]]
        download_checked "$url" "$archive" "$digest"
        unzip -q "$archive" -d "$work"
        mkdir -p "$tools/distributions"
        mv "$work/$gradle_version" "$distribution_dir"
        rm -f "$archive"
    fi
    "$distribution_dir/bin/gradle" "$task" --no-daemon --no-watch-fs         --max-workers=2 --console=plain --no-build-cache -Dorg.gradle.native=false         "-Dorg.gradle.java.home=$JAVA_HOME" -Pandroid.builder.sdkDownload=true         -Pkotlin.compiler.execution.strategy=in-process
}

js_dependencies() {
    get_tool node
    export PATH="$tools/node/bin:$PATH"
    if [[ -f pnpm-lock.yaml ]]; then
        corepack pnpm install --frozen-lockfile
    elif [[ -f yarn.lock ]]; then
        corepack yarn install
    elif [[ -f package-lock.json ]]; then
        npm ci --no-audit --no-fund
    else
        npm install --no-audit --no-fund
    fi
}
