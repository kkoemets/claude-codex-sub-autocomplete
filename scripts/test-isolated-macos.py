#!/usr/bin/env python3
"""Run installed IDE fixtures in a prepared Tart VM without host UI or input sharing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tart', required=True, type=Path)
    parser.add_argument('--vm', required=True)
    parser.add_argument('--image-identity', required=True, help='Recorded immutable image digest')
    parser.add_argument('--gradle-cache', required=True, type=Path, help='Dedicated test-only cache')
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('plugin_zip', type=Path)
    parser.add_argument('gradle_arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not re.fullmatch(r'(?:[^\s]+@)?sha256:[a-f0-9]{64}', args.image_identity):
        parser.error('Expected a recorded base-image SHA-256 digest')
    if not args.vm or args.vm.startswith('-') or '/' in args.vm or args.vm in ('.', '..'):
        parser.error('Expected a local VM name')
    repo = Path(__file__).resolve().parent.parent
    artifact = args.plugin_zip.resolve(strict=True)
    tart = args.tart.resolve(strict=True)
    cache = args.gradle_cache.resolve(strict=True)
    jdk = args.java_home.resolve(strict=True)
    if cache == Path.home() / '.gradle' or not str(cache).startswith(str(repo / 'out') + '/'):
        parser.error('Use a dedicated test cache under this repository out directory')
    if not artifact.is_file() or artifact.suffix != '.zip' or not (jdk / 'bin/java').is_file():
        parser.error('Expected an existing plugin ZIP and JDK home')
    os.environ['TART_NO_AUTO_PRUNE'] = '1'
    def stopped_config():
        value = json.loads(subprocess.check_output([str(tart), 'get', args.vm, '--format', 'json']))
        if value.get('Running') is not False or value.get('State') != 'stopped':
            raise RuntimeError('The selected private test VM must already be stopped')
        return value

    stopped_config()
    output = repo / 'out/isolated-macos-tests'
    output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix=time.strftime('run-%Y%m%dT%H%M%SZ-', time.gmtime()), dir=output))
    print(f'Reports and source snapshot: {run}', flush=True)
    work = run / 'work'
    work.mkdir()
    names = subprocess.check_output(['git', '-C', str(repo), 'ls-files', '--cached', '--others',
                                     '--exclude-standard', '-z']).decode().split('\0')
    manifest = {}
    for name in sorted(set(filter(None, names))):
        source = repo / name
        if not source.exists():
            continue
        if not source.resolve().is_relative_to(repo) or not source.is_file():
            raise RuntimeError(f'Expected a repository file: {name}')
        expected = sha256(source)
        target = work / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        if sha256(target) != expected or sha256(source) != expected:
            raise RuntimeError(f'Source changed during snapshot: {name}')
        manifest[name] = expected
    write_json(run / 'source-manifest.json', manifest)
    (run / 'artifact').mkdir()
    expected_hash = sha256(artifact)
    shutil.copy2(artifact, run / 'artifact/plugin.zip')
    if sha256(run / 'artifact/plugin.zip') != expected_hash or sha256(artifact) != expected_hash:
        raise RuntimeError('Publication archive changed during snapshot')
    (run / 'artifact/SHA256SUMS').write_text(f'{expected_hash}  plugin.zip\n')
    subprocess.run([str(tart), 'set', args.vm, '--cpu', '4', '--memory', '8192',
                    '--display', '1920x1080px', '--no-display-refit'], check=True)
    write_json(run / 'vm-config.json', stopped_config())
    vm_directory = Path(os.environ.get('TART_HOME', str(Path.home() / '.tart'))) / 'vms' / args.vm
    if (vm_directory / 'overlay.asif').exists():
        raise RuntimeError('This runner currently requires a standalone prepared VM')
    vm_files = {}
    for name in ['config.json', 'disk.img', 'nvram.bin']:
        path = vm_directory / name
        print(f'Recording prepared VM identity: {name}', flush=True)
        before = path.stat()
        vm_files[name] = sha256(path)
        after = path.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise RuntimeError('Prepared VM changed while recording its identity')
    stopped_config()
    write_json(run / 'prepared-vm-identity.json', dict(schemaVersion=1, vmName=args.vm,
               baseImageIdentity=args.image_identity, files=vm_files))
    launch = [str(tart), 'run', '--no-graphics', '--no-audio', '--no-clipboard',
              '--dir', f'run:{run}', '--dir', f'cache:{cache}', '--dir', f'jdk:{jdk}:ro', args.vm]
    receipt = dict(schemaVersion=1, kind='tart', completed=False, vmName=args.vm,
                   imageIdentity='sha256:' + sha256(run / 'prepared-vm-identity.json'), launchArgv=launch,
                   sourceManifestSha256=sha256(run / 'source-manifest.json'), artifactSha256=expected_hash)
    write_json(run / 'isolated-runner.json', receipt)
    tasks = args.gradle_arguments or ['autocompleteInstalledAndroidStudioTest']
    with (run / 'vm.log').open('w') as vm_log, (run / 'runtime.log').open('w') as runtime_log:
        vm = subprocess.Popen(launch, stdout=vm_log, stderr=subprocess.STDOUT)
        try:
            ready = False
            for attempt in range(90):
                if vm.poll() is not None:
                    raise RuntimeError('Private VM stopped during boot; see vm.log')
                try:
                    probe = subprocess.run([str(tart), 'exec', args.vm, '/usr/bin/true'],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                    ready = probe.returncode == 0
                except subprocess.TimeoutExpired:
                    pass
                if ready:
                    break
                if attempt % 12 == 0:
                    print('Waiting for the private guest agent to become ready', flush=True)
                time.sleep(2)
            if not ready:
                raise RuntimeError('Private guest agent did not become ready')
            command = [str(tart), 'exec', args.vm, '/usr/bin/env',
                       'PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin', 'python3',
                       '/Volumes/My Shared Files/run/work/scripts/ide-tests/run-macos.py', run.name, *tasks]
            write_json(run / 'guest-command.json', command)
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, bufsize=1)
            for line in process.stdout:
                print(line, end='', flush=True)
                runtime_log.write(line)
                runtime_log.flush()
            status = process.wait()
            receipt['hostTransportExitCode'] = status
            completion_path = run / 'guest-completion.json'
            if completion_path.exists():
                completion = json.loads(completion_path.read_text())
                receipt.update(guestWorkspace=completion['guestWorkspace'],
                               guestCommandExitCode=completion['guestCommandExitCode'])
                receipt['completed'] = status == 0 and completion.get('completed') is True
            write_json(run / 'isolated-runner.json', receipt)
            if status != 0 or not receipt['completed']:
                raise RuntimeError(f'Guest fixture failed; see {run}/runtime.log')
        finally:
            # Signal only our child, never stop a VM by name after a failed launch.
            if vm.poll() is None:
                vm.send_signal(signal.SIGINT)
            try:
                vm.wait(timeout=30)
            except subprocess.TimeoutExpired:
                vm.terminate()
                vm.wait(timeout=30)
    print(f'Private macOS fixture complete: {run}', flush=True)


if __name__ == '__main__':
    main()
