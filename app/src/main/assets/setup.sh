set -euo pipefail
base="$HOME/.kovka"
sdk="$base/sdk"
cli_revision=13114758
cli="$sdk/cmdline-tools/$cli_revision"
export ANDROID_USER_HOME="$base/android-user"
mkdir -p "$sdk/cmdline-tools" "$ANDROID_USER_HOME"

setup_log="$(mktemp)"
trap 'rm -f "$setup_log"' EXIT
rm -f "$base/ready"
printf 'Установка Git, Java и Android-инструментов\n'
packages=(git curl unzip aapt openjdk-17 openjdk-21 openjdk-25)
if ! { pkg update -y && pkg install -y "${packages[@]}"; } >"$setup_log" 2>&1; then
    tail -n 100 "$setup_log" >&2
    exit 1
fi

for requirement in 17:17.0.21.1 21:21.0.13.1 25:25.0.5.1; do
    version="${requirement%:*}"
    installed="$(dpkg-query -W -f='${Version}' "openjdk-$version")"
    if ! dpkg --compare-versions "$installed" ge "${requirement#*:}"; then
        echo "Пакет OpenJDK $version ещё не получил обновление безопасности на текущем зеркале Termux." >&2
        exit 1
    fi
done
export JAVA_HOME="$PREFIX/lib/jvm/java-25-openjdk"
if [[ ! -f "$cli/bin/sdkmanager" ]]; then
    scratch="$(mktemp -d)"
    trap 'rm -rf "$scratch"; rm -f "$setup_log"' EXIT
    curl -fSL --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-${cli_revision}_latest.zip \
        -o "$scratch/tools.zip"
    printf '%s  %s\n' '5fdcc763663eefb86a5b8879697aa6088b041e70' "$scratch/tools.zip" | sha1sum -c -
    unzip -q "$scratch/tools.zip" -d "$scratch"
    mv "$scratch/cmdline-tools" "$cli"
fi

printf 'Подготовка Android SDK\n'
set +o pipefail
if ! yes | bash "$cli/bin/sdkmanager" --sdk_root="$sdk" --licenses >"$setup_log" 2>&1; then
    tail -n 100 "$setup_log" >&2
    exit 1
fi
set -o pipefail
"$JAVA_HOME/bin/java" -version
aapt2 version
touch "$base/ready"
printf 'Среда готова\n'
