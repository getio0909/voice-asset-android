#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "verify-release: $*" >&2
  exit 1
}

if (( $# != 2 )); then
  echo "usage: $0 <version> <release-directory>" >&2
  exit 2
fi

version=$1
release_argument=$2
[[ $version =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]] ||
  fail "invalid semantic version tag: $version"
[[ -d $release_argument ]] || fail "release directory does not exist: $release_argument"
release_dir=$(cd -- "$release_argument" && pwd -P)
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)

for command in node sha256sum awk find grep jar mktemp; do
  command -v "$command" >/dev/null 2>&1 || fail "required command is unavailable: $command"
done

cd -- "$repo_root"
version_name=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)
[[ -n $version_name && $version == "v$version_name" ]] ||
  fail "tag $version does not match Android version v$version_name"

apk_name="voiceasset-android-$version.apk"
aab_name="voiceasset-android-$version.aab"
sbom_name=voiceasset-android.cdx.json
expected_names=(SHA256SUMS "$aab_name" "$apk_name" "$sbom_name")
mapfile -t expected_names < <(printf '%s\n' "${expected_names[@]}" | LC_ALL=C sort)
mapfile -t actual_names < <(find "$release_dir" -mindepth 1 -maxdepth 1 -printf '%f\n' | LC_ALL=C sort)
[[ $(printf '%s\n' "${actual_names[@]}") == $(printf '%s\n' "${expected_names[@]}") ]] ||
  fail "release directory contains missing or unexpected files"

for name in "${actual_names[@]}"; do
  [[ -f $release_dir/$name && ! -L $release_dir/$name ]] ||
    fail "release entry must be a regular, non-symlink file: $name"
done

expected_checksum_names=("./$aab_name" "./$apk_name" "./$sbom_name")
mapfile -t expected_checksum_names < <(printf '%s\n' "${expected_checksum_names[@]}" | LC_ALL=C sort)
mapfile -t checksum_names < <(
  awk '{name = $2; sub(/^\*/, "", name); print name}' "$release_dir/SHA256SUMS" | LC_ALL=C sort
)
[[ $(printf '%s\n' "${checksum_names[@]}") == $(printf '%s\n' "${expected_checksum_names[@]}") ]] ||
  fail "SHA256SUMS does not cover exactly the release artifacts"
while read -r checksum name extra; do
  [[ -z ${extra:-} && $checksum =~ ^[0-9a-f]{64}$ && $name == \*./* ]] ||
    fail "malformed SHA256SUMS entry"
done <"$release_dir/SHA256SUMS"
(
  cd -- "$release_dir"
  sha256sum -c SHA256SUMS
)
node scripts/check-sbom.mjs "$release_dir/$sbom_name"

find_sdk_tool() {
  local tool=$1
  local resolved
  if resolved=$(command -v "$tool" 2>/dev/null); then
    printf '%s\n' "$resolved"
    return
  fi

  local sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  [[ -n $sdk_root ]] || fail "ANDROID_HOME or ANDROID_SDK_ROOT is required"
  if command -v cygpath >/dev/null 2>&1; then
    sdk_root=$(cygpath -u "$sdk_root")
  fi
  mapfile -t matches < <(
    find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f \
      \( -name "$tool" -o -name "$tool.exe" -o -name "$tool.bat" \) -print | sort -V
  )
  (( ${#matches[@]} > 0 )) || fail "Android SDK tool is unavailable: $tool"
  printf '%s\n' "${matches[-1]}"
}

apk="$release_dir/$apk_name"
aab="$release_dir/$aab_name"
aapt2=$(find_sdk_tool aapt2)
apksigner=$(find_sdk_tool apksigner)
badging=$("$aapt2" dump badging "$apk")
grep -Fq "package: name='com.voiceasset.android'" <<<"$badging" || fail "unexpected APK package"
grep -Fq "versionName='$version_name'" <<<"$badging" || fail "unexpected APK version"
grep -Fq "minSdkVersion:'26'" <<<"$badging" || fail "unexpected APK minimum SDK"
grep -Fq "targetSdkVersion:'36'" <<<"$badging" || fail "unexpected APK target SDK"
"$apksigner" verify --verbose --print-certs "$apk" >/dev/null ||
  fail "release APK signature verification failed"

temp_root=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
listing=$(mktemp "$temp_root/voiceasset-android-aab.XXXXXX")
cleanup() {
  case $listing in
    "$temp_root"/voiceasset-android-aab.*) rm -f -- "$listing" ;;
    *) echo "verify-release: refusing to clean unexpected path: $listing" >&2 ;;
  esac
}
trap cleanup EXIT
jar tf "$aab" >"$listing"
grep -Fxq BundleConfig.pb "$listing" || fail "AAB is missing BundleConfig.pb"
grep -Fxq base/manifest/AndroidManifest.xml "$listing" || fail "AAB is missing the base manifest"
grep -Eq '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$' "$listing" ||
  fail "release AAB signature metadata is missing"
jarsigner -verify "$aab" >/dev/null || fail "release AAB signature verification failed"

echo "verified signed Android APK/AAB metadata, SBOM, signatures, and SHA-256 checksums"
