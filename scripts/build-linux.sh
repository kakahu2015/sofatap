#!/bin/bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
architecture="${1:-x64}"
case "$architecture" in
  x64|arm64) ;;
  *) echo "Usage: bash scripts/build-linux.sh [x64|arm64]" >&2; exit 1 ;;
esac
if [ "$#" -gt 1 ]; then
  echo "Build one architecture at a time: x64 or arm64." >&2
  exit 1
fi
runtime="linux-$architecture"
dotnet_cmd="${SOFATAP_DOTNET:-$repo_dir/.tools/dotnet/dotnet}"
if [ ! -x "$dotnet_cmd" ]; then dotnet_cmd="$(command -v dotnet || true)"; fi
if [ -z "$dotnet_cmd" ]; then
  echo "Install the .NET 10 SDK, or set SOFATAP_DOTNET to its executable." >&2
  exit 1
fi
for utility in python3 tar; do
  if ! command -v "$utility" >/dev/null; then echo "Required build utility is missing: $utility" >&2; exit 1; fi
done
build_dir="$repo_dir/artifacts/linux-build/$runtime"
release_name="SofaTap-Linux-$architecture"
release_dir="$repo_dir/artifacts/$release_name"
publish_dir="$build_dir/self-contained"
# Recreate only generated files for the selected architecture.
rm -rf "$publish_dir" "$release_dir"
mkdir -p "$publish_dir" "$release_dir/lib/linux"
"$dotnet_cmd" publish host/SofaTap.Host.csproj -c Release -r "$runtime" \
  --artifacts-path "$build_dir/intermediate" --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true \
  -p:PublishTrimmed=false -p:DebugType=None -p:DebugSymbols=false -o "$publish_dir"
python3 - "$publish_dir/SofaTap" "$architecture" <<'PY'
from pathlib import Path
import struct
import sys

header = Path(sys.argv[1]).read_bytes()[:20]
expected = {"x64": 62, "arm64": 183}[sys.argv[2]]
if len(header) != 20 or header[:6] != b"\x7fELF\x02\x01" or struct.unpack("<H", header[18:20])[0] != expected:
    raise SystemExit("Published receiver is not a 64-bit Linux ELF for the requested architecture")
PY
cp "$publish_dir/SofaTap" "$release_dir/lib/SofaTapHost"
cp desktop/linux/sofatap.py desktop/linux/autostart.py desktop/linux/receiver_update.py desktop/linux/receiver_instance.py desktop/linux/input_worker.py desktop/linux/input_common.py \
  desktop/linux/wayland_input.py desktop/linux/x11_input.py desktop/linux/text_focus_worker.py "$release_dir/lib/linux/"
cp scripts/linux/sofatap "$release_dir/sofatap"
cp scripts/linux/sofatap.desktop "$release_dir/sofatap.desktop"
cp desktop/macos/Assets/AppIcon.png "$release_dir/sofatap.png"
chmod 755 "$release_dir/sofatap" "$release_dir/lib/SofaTapHost"
chmod 644 "$release_dir/lib/linux/"*.py "$release_dir/sofatap.desktop" "$release_dir/sofatap.png"
cp desktop/linux/README.md "$release_dir/README.md"
cp LICENSE THIRD-PARTY-NOTICES.md "$release_dir/"
"$dotnet_cmd" msbuild host/SofaTap.Host.csproj -t:ResolveFrameworkReferences \
  -p:RuntimeIdentifier="$runtime" -p:SelfContained=true -p:PublishSingleFile=true \
  -p:UseArtifactsOutput=true -p:ArtifactsPath="$build_dir/intermediate" \
  -getItem:ResolvedSingleFileHostPack > "$build_dir/host-pack.json"
python3 scripts/package-licenses.py \
  --assets "$build_dir/intermediate/obj/SofaTap.Host/project.assets.json" \
  --host-pack "$build_dir/host-pack.json" \
  --runtime "$runtime" --destination "$release_dir/licenses"
python3 - "$repo_dir" "$release_dir" "$runtime" "$dotnet_cmd" <<'PY'
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

root, release = map(Path, sys.argv[1:3])
manifest = {
    "runtime": sys.argv[3],
    "updateVersion": (root / "updates/desktop-version.txt").read_text().strip(),
    "files": {p.relative_to(release).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(release.rglob("*")) if p.is_file() and p.name != "BUILD-MANIFEST.json"},
    "version": ET.parse(root / "host/SofaTap.Host.csproj").findtext(".//Version"),
    "sourceRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
    "sourceDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip()),
    "sdkVersion": subprocess.check_output([sys.argv[4], "--version"], cwd=root, text=True).strip(),
    "builtAt": datetime.now(timezone.utc).isoformat(),
    "backendSHA256": hashlib.sha256((release / "lib/SofaTapHost").read_bytes()).hexdigest(),
    "frontendSHA256": hashlib.sha256((release / "lib/linux/sofatap.py").read_bytes()).hexdigest(),
    "autostartSHA256": hashlib.sha256((release / "lib/linux/autostart.py").read_bytes()).hexdigest(),
    "instanceSHA256": hashlib.sha256((release / "lib/linux/receiver_instance.py").read_bytes()).hexdigest(),
    "inputWorkerSHA256": hashlib.sha256((release / "lib/linux/input_worker.py").read_bytes()).hexdigest(),
    "textFocusWorkerSHA256": hashlib.sha256((release / "lib/linux/text_focus_worker.py").read_bytes()).hexdigest(),
    "pairingUI": "native GTK 3",
    "inputBackends": ["X11 XTest", "Wayland RemoteDesktop portal"],
    "bundledDotnetRuntime": True,
    "systemDependencies": ["Python 3.10+", "PyGObject", "GTK 3", "libX11, libXtst and libxdo3 for X11", "RemoteDesktop portal for Wayland"],
    "physicalInputVerified": False,
}
(release / "BUILD-MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY
# Use Python's tar writer to avoid host-specific extended attributes and resource forks.
python3 - "$release_dir" "$repo_dir/artifacts/$release_name.tar.gz" <<'PY'
import hashlib
from pathlib import Path
import sys
import tarfile

release, archive = map(Path, sys.argv[1:])
with tarfile.open(archive, "w:gz", format=tarfile.PAX_FORMAT) as package:
    def clean(info):
        info.uid = info.gid = 0
        info.uname = info.gname = ""
        info.pax_headers = {}
        return info
    package.add(release, arcname=release.name, filter=clean)
checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_suffix(archive.suffix + ".sha256").write_text(checksum + "  " + archive.name + "\n", encoding="utf-8")
PY
echo "Built artifacts/$release_name.tar.gz"
