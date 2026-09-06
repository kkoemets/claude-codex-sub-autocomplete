#!/usr/bin/env python3
"""Guest-only entry point. All IDE activation, keyboard input and capture stay in the VM."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    share = Path('/Volumes/My Shared Files')
    archive = share / 'run'
    if platform.system() != 'Darwin' or not (archive / 'isolated-runner.json').is_file():
        raise RuntimeError('This entry point requires the private macOS runner share')
    run_name, *tasks = sys.argv[1:]
    if not run_name.startswith('run-') or '/' in run_name or '..' in run_name:
        raise RuntimeError('Invalid isolated run name')
    root = Path.home() / 'autocomplete' / run_name
    if root.exists():
        raise RuntimeError('Refusing to reuse a previous guest workspace')
    root.mkdir(parents=True)
    work = root / 'work'
    shutil.copytree(archive / 'work', work, symlinks=True)
    shutil.copytree(archive / 'artifact', root / 'artifact')
    os.chdir(work)
    workspace = subprocess.check_output(['/bin/pwd', '-P'], text=True).strip()
    manifest_path = archive / 'source-manifest.json'
    manifest_hash = sha256(manifest_path)
    manifest = json.loads(manifest_path.read_text())
    for name, expected in manifest.items():
        target = work / name
        if not target.resolve().is_relative_to(work.resolve()) or sha256(target) != expected:
            raise RuntimeError(f'Guest source differs from supplied manifest: {name}')
    artifact_hash = sha256(root / 'artifact/plugin.zip')
    runner = json.loads((archive / 'isolated-runner.json').read_text())
    if artifact_hash != runner['artifactSha256'] or manifest_hash != runner['sourceManifestSha256']:
        raise RuntimeError('Guest inputs differ from the host snapshot')
    env = dict(os.environ, JAVA_HOME=str(share / 'jdk'), GRADLE_USER_HOME=str(share / 'cache'))
    env['PATH'] = str(share / 'jdk/bin') + ':' + env['PATH']
    for variable in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']:
        env.pop(variable, None)
    console = subprocess.check_output(['/usr/bin/stat', '-f', '%Su', '/dev/console'], text=True).strip()
    user = subprocess.check_output(['/usr/bin/id', '-un'], text=True).strip()
    if console != user or console in ('root', 'loginwindow'):
        raise RuntimeError(f'Guest must run as its logged-in desktop user; console={console}, user={user}')
    metadata = dict(system=platform.system(), machine=platform.machine(), macOS=platform.mac_ver()[0],
                    consoleUser=console, guestWorkspace=workspace, startedAt=int(time.time()))
    (archive / 'guest-environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print('Private guest environment: ' + json.dumps(metadata), flush=True)
    with (archive / 'guest-display.txt').open('w') as display:
        subprocess.run(['/usr/sbin/system_profiler', 'SPDisplaysDataType'], stdout=display, check=True)
    command = ['./gradlew', *tasks, f'-PideTestPluginPath={root}/artifact/plugin.zip',
               '-PideTestRepetitions=1', '-PrequirePhysicalTyping=true',
               '-PideTestExternalEditorInput=false', '-PideTestExternalTerminalInput=false',
               '--no-daemon', '--no-parallel', '--no-watch-fs', '--continue', '--max-workers=2']
    status = 1
    try:
        status = subprocess.run(command, env=env).returncode
    finally:
        # Preserve failures too. Success is recorded only after all output is archived.
        for name in ['build', 'out', 'allure-results']:
            if (work / name).exists():
                shutil.copytree(work / name, archive / 'work' / name, symlinks=True, dirs_exist_ok=True)
        receipt = dict(schemaVersion=1, guestWorkspace=workspace, sourceManifestSha256=manifest_hash,
                       artifactSha256=artifact_hash, guestCommandExitCode=status, completed=status == 0)
        (archive / 'guest-completion.json').write_text(json.dumps(receipt, indent=2) + '\n')
    sys.exit(status)


if __name__ == '__main__':
    main()
