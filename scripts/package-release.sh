#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "package-release: $*" >&2
  exit 1
}

if (( $# != 2 )); then
  echo "usage: $0 <version> <output-directory>" >&2
  exit 2
fi

version=$1
output_argument=$2
[[ $version =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]] ||
  fail "version must be a semantic version tag such as v1.2.3"

for command in node sha256sum find mktemp; do
  command -v "$command" >/dev/null 2>&1 || fail "required command is unavailable: $command"
done

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd -- "$repo_root"
version_name=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)
base_version=${version#v}
base_version=${base_version%%-*}
base_version=${base_version%%+*}
[[ -n $version_name && $base_version == "$version_name" ]] ||
  fail "tag $version does not match Android version v$version_name"

mapfile -t apks < <(find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' ! -name '*-unsigned.apk' -print | LC_ALL=C sort)
mapfile -t bundles < <(find app/build/outputs/bundle/release -maxdepth 1 -type f -name '*.aab' ! -name '*-unsigned.aab' -print | LC_ALL=C sort)
(( ${#apks[@]} == 1 )) || fail "expected exactly one release APK"
(( ${#bundles[@]} == 1 )) || fail "expected exactly one release AAB"
[[ ${apks[0]} != *-unsigned.apk ]] || fail "release APK must be signed"
[[ -f app/build/reports/cyclonedx-direct/bom.json ]] || fail "CycloneDX SBOM is missing"

for source in "${apks[0]}" "${bundles[0]}" app/build/reports/cyclonedx-direct/bom.json; do
  [[ -f $source && ! -L $source ]] || fail "release source must be a regular, non-symlink file: $source"
done
node scripts/check-sbom.mjs app/build/reports/cyclonedx-direct/bom.json

mkdir -p -- "$output_argument"
output_dir=$(cd -- "$output_argument" && pwd -P)
[[ -z $(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit) ]] ||
  fail "output directory must be empty: $output_dir"

temp_root=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
staging=$(mktemp -d "$temp_root/voiceasset-android-release.XXXXXX")
cleanup() {
  case $staging in
    "$temp_root"/voiceasset-android-release.*) rm -rf -- "$staging" ;;
    *) echo "package-release: refusing to clean unexpected path: $staging" >&2 ;;
  esac
}
trap cleanup EXIT

cp -- "${apks[0]}" "$staging/voiceasset-android-$version.apk"
cp -- "${bundles[0]}" "$staging/voiceasset-android-$version.aab"
cp -- app/build/reports/cyclonedx-direct/bom.json "$staging/voiceasset-android.cdx.json"
(
  cd -- "$staging"
  sha256sum --binary -- ./*.aab ./*.apk ./*.json >SHA256SUMS
)
mv -- "$staging"/* "$output_dir/"

echo "packaged signed Android APK, AAB, SBOM, and checksums in $output_dir"
