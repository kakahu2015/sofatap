#!/bin/bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
mode=build
if [ "$#" -gt 0 ]; then
  case "$1" in
    --archive) mode=archive ;;
    --help) echo 'Usage: bash scripts/build-macos-store.sh [--archive]'; exit 0 ;;
    *) echo 'Usage: bash scripts/build-macos-store.sh [--archive]' >&2; exit 1 ;;
  esac
fi
test "$#" -le 1 || { echo 'Unexpected extra arguments.' >&2; exit 1; }
test "$(uname -s)" = Darwin || { echo 'Build the Mac App Store app on macOS with Xcode.' >&2; exit 1; }
for utility in xcodegen xcrun python3 codesign ditto iconutil sips lipo; do
  command -v "$utility" >/dev/null || { echo "Missing build utility: $utility" >&2; exit 1; }
done
dotnet_cmd="${SOFATAP_DOTNET:-$repo_dir/.tools/dotnet/dotnet}"
if [ ! -x "$dotnet_cmd" ]; then dotnet_cmd="$(command -v dotnet || true)"; fi
test -n "$dotnet_cmd" || { echo 'Install .NET 10 or set SOFATAP_DOTNET.' >&2; exit 1; }
case "$("$dotnet_cmd" --version)" in 10.*) ;; *) echo '.NET 10 SDK is required.' >&2; exit 1 ;; esac
build_dir="$repo_dir/artifacts/macos-store"
inputs="$build_dir/inputs"
mkdir -p "$inputs/Helpers" "$inputs/Resources"

for architecture in arm64 x64; do
  runtime="osx-$architecture"
  arch_build="$build_dir/$runtime"
  publish="$arch_build/publish"
  mkdir -p "$arch_build" "$inputs/Helpers/$architecture"
  # Only this script's generated publish folder is replaced.
  rm -rf "$publish"
  "$dotnet_cmd" publish host/SofaTap.Host.csproj -c Release -r "$runtime" \
    --artifacts-path "$arch_build/intermediate" --self-contained true \
    -p:SofatapMacAppStore=true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true \
    -p:PublishTrimmed=false -p:DebugType=None -p:DebugSymbols=false -o "$publish"
  expected_arch=arm64
  if [ "$architecture" = x64 ]; then expected_arch=x86_64; fi
  test "$(lipo -archs "$publish/SofaTap")" = "$expected_arch" || { echo 'Backend architecture mismatch.' >&2; exit 1; }
  cp "$publish/SofaTap" "$inputs/Helpers/$architecture/SofaTapHost"
  python3 - "$publish" <<'PY'
import json
from pathlib import Path
import sys
for item in Path(sys.argv[1]).iterdir():
    if item.name == 'SofaTap':
        continue
    if item.name != 'SofaTap.staticwebassets.endpoints.json' or json.loads(item.read_text())['Endpoints']:
        raise SystemExit('Review unexpected publish content before packaging: ' + item.name)
PY
  "$dotnet_cmd" msbuild host/SofaTap.Host.csproj -t:ResolveFrameworkReferences \
    -p:SofatapMacAppStore=true -p:RuntimeIdentifier="$runtime" -p:SelfContained=true \
    -p:PublishSingleFile=true -p:UseArtifactsOutput=true -p:ArtifactsPath="$arch_build/intermediate" \
    -getItem:ResolvedSingleFileHostPack > "$arch_build/host-pack.json"
  python3 scripts/package-licenses.py \
    --assets "$arch_build/intermediate/obj/SofaTap.Host/project.assets.json" \
    --host-pack "$arch_build/host-pack.json" --runtime "$runtime" \
    --destination "$inputs/Resources/licenses/$runtime"
done

cp LICENSE THIRD-PARTY-NOTICES.md "$inputs/Resources/"
cp packaging/macos-store/PrivacyInfo.xcprivacy "$inputs/Resources/"
iconset="$build_dir/SofaTap.iconset"
mkdir -p "$iconset"
icon_source="$repo_dir/desktop/macos/Assets/AppIcon.png"
for points in 16 32 128 256 512; do
  sips -z "$points" "$points" "$icon_source" --out "$iconset/icon_${points}x${points}.png" >/dev/null
  pixels=$((points * 2))
  sips -z "$pixels" "$pixels" "$icon_source" --out "$iconset/icon_${points}x${points}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$inputs/Resources/SofaTap.icns"
xcodegen generate --spec packaging/macos-store/project.yml
project="$repo_dir/packaging/macos-store/SofatapReceiver.xcodeproj"

if [ "$mode" = archive ]; then
  command -v asc >/dev/null || { echo 'Install asc for the archive command.' >&2; exit 1; }
  archive_path="${SOFATAP_STORE_ARCHIVE_PATH:-$build_dir/SofatapReceiver.xcarchive}"
  test ! -e "$archive_path" || { echo 'Archive already exists; choose a new SOFATAP_STORE_ARCHIVE_PATH.' >&2; exit 1; }
  asc xcode archive --project "$project" --scheme SofatapReceiver --configuration Release \
    --archive-path "$archive_path" --xcodebuild-flag=-destination \
    --xcodebuild-flag=generic/platform=macOS --output json
  echo "Created archive at $archive_path. Export, upload and review are separate steps."
  exit 0
fi

xcrun xcodebuild -project "$project" -scheme SofatapReceiver -configuration Release \
  -destination 'generic/platform=macOS' -derivedDataPath "$build_dir/DerivedData" \
  CODE_SIGNING_ALLOWED=NO build
app="$build_dir/DerivedData/Build/Products/Release/SofatapReceiver.app"
for architecture in arm64 x64; do
  codesign --force --sign - --options runtime \
    --identifier "com.chekusu.sofatap.receiver.host.$architecture" \
    --entitlements scripts/macos-store/Helper.entitlements.plist \
    "$app/Contents/Helpers/$architecture/SofaTapHost"
done
codesign --force --sign - --options runtime --identifier com.chekusu.sofatap.receiver \
  --entitlements scripts/macos-store/Receiver.entitlements.plist "$app"
codesign --verify --deep --strict --verbose=2 "$app"
python3 scripts/macos-store/validate-bundle.py "$app" --output "$build_dir/BUILD-MANIFEST.json"
echo "Built local App Sandbox app: $app"
echo 'This ad-hoc build is not uploaded, approved or validated for real remote input.'
