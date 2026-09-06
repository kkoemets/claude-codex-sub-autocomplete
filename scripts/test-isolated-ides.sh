#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 /absolute/path/to/plugin.zip [Gradle tasks or options]" >&2
  exit 2
fi
repo_root=$(cd "$(dirname "$0")/.." && pwd)
plugin_zip=$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve(strict=True))' "$1")
shift
[[ -f "$plugin_zip" && "$plugin_zip" == *.zip ]] || { echo 'Expected an existing plugin ZIP.' >&2; exit 2; }
test_uid=$(id -u)
[[ "$test_uid" -ne 0 ]] || { echo 'Run this script as a normal user.' >&2; exit 2; }
docker info >/dev/null

output_root="$repo_root/out/isolated-ide-tests"
mkdir -p "$output_root/gradle-cache"
run_dir=$(mktemp -d "$output_root/run-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
container_name="subscription-autocomplete-ide-$(date -u +%Y%m%d%H%M%S)-$$"
image_name="subscription-autocomplete-ide-tests:local"
echo "Reports and source snapshot: $run_dir"

# Copy only repository files, including current edits. Keep the original checkout,
# credentials, and host display out of the container.
python3 - "$repo_root" "$run_dir" "$plugin_zip" <<'PY'
from pathlib import Path
import hashlib, json, shutil, subprocess, sys
repo, run, artifact = map(Path, sys.argv[1:])
work = run / 'work'
work.mkdir()
names = subprocess.check_output(
    ['git', '-C', str(repo), 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
).decode().split('\0')
manifest = {}
def copy_verified(source, target):
    expected = hashlib.sha256(source.read_bytes()).hexdigest()
    shutil.copy2(source, target)
    actual = hashlib.sha256(target.read_bytes()).hexdigest()
    if actual != expected or hashlib.sha256(source.read_bytes()).hexdigest() != expected:
        raise SystemExit(f'Source changed while creating the isolated snapshot: {source}')
    return actual

for name in sorted(set(filter(None, names))):
    source = repo / name
    if not source.exists():
        continue
    if not source.resolve().is_relative_to(repo.resolve()) or not source.is_file():
        raise SystemExit(f'Expected a repository file: {name}')
    target = work / name
    target.parent.mkdir(parents=True, exist_ok=True)
    manifest[name] = copy_verified(source, target)
(run / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
(run / 'artifact').mkdir()
artifact_hash = copy_verified(artifact, run / 'artifact' / 'plugin.zip')
(run / 'artifact' / 'SHA256SUMS').write_text(
    artifact_hash + '  plugin.zip\n',
)
PY

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker build --platform linux/amd64 --build-arg "TEST_UID=$test_uid" \
  --iidfile "$run_dir/image-id.txt" -t "$image_name" \
  "$run_dir/work/scripts/ide-tests" 2>&1 | tee "$run_dir/image-build.log"
image_id=$(cat "$run_dir/image-id.txt")
docker image inspect "$image_id" > "$run_dir/image.json"

if [[ $# -eq 0 ]]; then
  set -- autocompleteCrossIdeTest
fi
docker create --init --name "$container_name" --platform linux/amd64 \
  --cpus=6 --memory=8g --shm-size=2g \
  --mount "type=bind,src=$run_dir/work,dst=/work" \
  --mount "type=bind,src=$run_dir/artifact,dst=/artifact,readonly" \
  --mount "type=bind,src=$output_root/gradle-cache,dst=/home/tester/.gradle" \
  "$image_id" xvfb-run --auto-servernum \
  --server-args='-screen 0 1920x1080x24 -ac -nolisten tcp' \
  dbus-run-session -- bash scripts/ide-tests/run-inside.sh \
  "$@" -PideTestPluginPath=/artifact/plugin.zip -PideTestRepetitions=1 \
  --no-daemon --no-parallel --no-watch-fs --continue > "$run_dir/container-id.txt"
docker inspect "$container_name" > "$run_dir/container.json"
set +e
docker start --attach "$container_name" 2>&1 | tee "$run_dir/runtime.log"
attach_status=$?
set -e
docker inspect "$container_name" > "$run_dir/container-final.json"
container_status=$(docker inspect "$container_name" --format '{{.State.ExitCode}}')
[[ "$attach_status" -eq 0 ]] || exit "$attach_status"
exit "$container_status"
