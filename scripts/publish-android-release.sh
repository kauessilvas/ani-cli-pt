#!/usr/bin/env bash
set -euo pipefail

# Build with the existing local signing key. Publishing happens only with --publish.
repo="kauessilvas/ani-cli-pt"
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:---check-only}"
notes="${2:-}"
if [[ "$mode" != "--check-only" && "$mode" != "--publish" ]]; then
    printf 'Uso: %s [--check-only | --publish ARQUIVO_DE_NOTAS]\n' "$0" >&2
    exit 2
fi
if [[ "$mode" == "--publish" && ! -f "$notes" ]]; then
    printf 'Informe um arquivo existente com as notas da release.\n' >&2
    exit 2
fi
if [[ "$mode" == "--publish" ]]; then notes="$(realpath -- "$notes")"; fi
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_HOME" ]]; then printf 'Configure ANDROID_HOME ou ANDROID_SDK_ROOT.\n' >&2; exit 2; fi
for tool in gh java sha256sum; do command -v "$tool" >/dev/null || { printf 'Comando ausente: %s\n' "$tool" >&2; exit 2; }; done
build_tools="$ANDROID_HOME/build-tools/35.0.0"
aapt="$build_tools/aapt"
apksigner="$build_tools/apksigner"
[[ -x "$aapt" && -x "$apksigner" ]] || { printf 'Instale Android SDK Build Tools 35.0.0.\n' >&2; exit 2; }
tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "$tmp_dir"' EXIT
cd "$root"
if [[ "$mode" == "--publish" ]]; then
    [[ -z "$(git status --porcelain)" ]] || { printf 'O repositório tem alterações não commitadas.\n' >&2; exit 1; }
    commit="$(git rev-parse HEAD)"
    remote_commit="$(git ls-remote https://github.com/$repo.git refs/heads/ani-cli-pt | cut -f1)"
    [[ "$commit" == "$remote_commit" ]] || { printf 'Envie o commit atual para ani-cli-pt antes de publicar.\n' >&2; exit 1; }
fi
"$root/android-app/gradlew" -p "$root/android-app" :app:testDebugUnitTest :app:assembleDebug
apk_source="$root/android-app/app/build/outputs/apk/debug/app-debug.apk"
apk="$root/android-app/app/build/outputs/apk/debug/ani-cli-pt.apk"
cp -- "$apk_source" "$apk"
metadata="$("$aapt" dump badging "$apk" | sed -n '1p')"
package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$metadata")"
version="$(sed -n "s/^package: name='[^']*' versionCode='[^']*' versionName='\([^']*\)'.*/\1/p" <<< "$metadata")"
version_code="$(sed -n "s/^package: name='[^']*' versionCode='\([0-9]*\)'.*/\1/p" <<< "$metadata")"
[[ "$package" == "dev.aniclipt.app" && "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'Pacote ou versão do APK inválido.\n' >&2; exit 1; }
"$apksigner" verify "$apk"
gh release download --repo "$repo" --pattern ani-cli-pt.apk --dir "$tmp_dir"
old_metadata="$("$aapt" dump badging "$tmp_dir/ani-cli-pt.apk" | sed -n '1p')"
old_version_code="$(sed -n "s/^package: name='[^']*' versionCode='\([0-9]*\)'.*/\1/p" <<< "$old_metadata")"
[[ "$version_code" =~ ^[0-9]+$ && "$old_version_code" =~ ^[0-9]+$ ]] || { printf 'Não foi possível conferir os códigos de versão.\n' >&2; exit 1; }
if [[ "$mode" == "--publish" && "$version_code" -le "$old_version_code" ]]; then
    printf 'O versionCode deve ser maior que o da última release.\n' >&2
    exit 1
fi
new_cert="$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
old_cert="$("$apksigner" verify --print-certs "$tmp_dir/ani-cli-pt.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ -n "$new_cert" && "$new_cert" == "$old_cert" ]] || { printf 'A assinatura difere da última release; atualização recusada.\n' >&2; exit 1; }
printf 'APK validado: %s (versão %s)\n' "$apk" "$version"
sha256sum "$apk"
if [[ "$mode" == "--publish" ]]; then
    tag="v$version"
    if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then printf 'A release %s já existe.\n' "$tag" >&2; exit 1; fi
    gh release create "$tag" "$apk" --repo "$repo" --target "$commit" --title "ani-cli-pt Android $version" --notes-file "$notes" --draft
    gh release edit "$tag" --repo "$repo" --draft=false --latest
fi
