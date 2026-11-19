#!/bin/bash
set -euo pipefail
job="$1"
repository="$2"
revision="$3"
project_path="$4"
task="${5:-assembleDebug}"
requested_java="${6:-auto}"
work="/kovka/jobs/$job"
mkdir -p "$work/source" "$work/output"
source /kovka/scripts/tools.sh

echo '::kovka::environment'
prepare_base

echo '::kovka::source'
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

project="$(pwd -P)"
js_project=0
is_apk_output() {
    find "$project" -type f \( -path '*/build/outputs/apk/*' -o -path '*/build/app/outputs/flutter-apk/*' \) -name '*.apk' "$@"
}
is_apk_output -delete

echo '::kovka::tools'
if [[ -f pubspec.yaml ]] && grep -Eq '(^flutter:|sdk:[[:space:]]*flutter)' pubspec.yaml; then
    printf 'Проект Flutter\n'
    if [[ -f android/gradle/wrapper/gradle-wrapper.properties ]]; then
        read_wrapper android
        java_for_gradle "$gradle_major" "$gradle_minor"
    else
        java_for_gradle 9 1
    fi
    prepare_android
    prepare_flutter_android
    get_flutter
    export PATH="$tools/flutter/bin:$PATH"
    flutter config --no-analytics --android-sdk "$ANDROID_HOME"
    flutter pub get
    echo '::kovka::compile'
    flutter build apk --debug
else
    if [[ -f package.json ]] && python3 -c 'import json; p=json.load(open("package.json")); d=p.get("dependencies",{}) | p.get("devDependencies",{}); raise SystemExit(not any(x in d for x in ("react-native","expo","@capacitor/core")))' ; then
        printf 'Проект JavaScript для Android\n'
        js_dependencies
        js_project=1
        if [[ ! -d android ]] && [[ -x node_modules/.bin/expo ]]; then
            node_modules/.bin/expo prebuild --platform android --no-install
        fi
        if [[ -x node_modules/.bin/cap ]]; then
            if python3 -c 'import json; raise SystemExit(not json.load(open("package.json")).get("scripts",{}).get("build"))'; then
                npm run build
            fi
            node_modules/.bin/cap sync android
        fi
    fi
    if [[ ! -f gradle/wrapper/gradle-wrapper.properties && -f android/gradle/wrapper/gradle-wrapper.properties ]]; then
        cd android
    fi
    read_wrapper .
    java_for_gradle "$gradle_major" "$gradle_minor"
    prepare_android
    if (( js_project )); then
        prepare_arm_ndk
        if [[ "$task" == assembleDebug ]] && python3 -c 'import json,sys; p=json.load(open(sys.argv[1])); d=p.get("dependencies",{}) | p.get("devDependencies",{}); raise SystemExit(not any(x in d for x in ("react-native","expo")))' "$project/package.json"; then
            task=assembleRelease
        fi
    fi
    if [[ -e local.properties || -L local.properties ]]; then
        sed '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' local.properties > "$work/local.properties"
        rm local.properties
        mv "$work/local.properties" local.properties
    fi
    printf '\nsdk.dir=%s\n' "$ANDROID_HOME" >> local.properties
    echo '::kovka::compile'
    gradle_build "$task"
fi

echo '::kovka::collect'
count=0
while IFS= read -r -d '' apk; do
    count=$((count + 1))
    filename="${apk##*/}"
    name="$(printf '%s' "${filename%.apk}" | tr -c 'A-Za-z0-9._-' '_' | cut -c 1-96)"
    if [[ "$name" == *-unsigned ]]; then
        sign_apk "$apk" "$work/output/$count-${name%-unsigned}.apk"
    else
        cp -- "$apk" "$work/output/$count-$name.apk"
    fi
done < <(is_apk_output -print0)
if (( count == 0 )); then
    echo 'Gradle завершился, но в папке сборки нет APK.'
    exit 1
fi
printf 'APK готовы: %s\n' "$count"
