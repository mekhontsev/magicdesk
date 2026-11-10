"""Private API filesystems and a real console for userspace init tests."""
import shlex
import json
from pathlib import Path
import subprocess

GUEST_PATH = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/nix/var/nix/profiles/system/sw/bin'

TRACE_SYSCALLS = ('%file,%process,%network,ioctl,mount_setattr,open_tree,move_mount,fsopen,fsconfig,fsmount,'
                  'prctl,seccomp,epoll_create1,epoll_ctl,epoll_pwait,epoll_pwait2')

OPENRC_PREPARE = r'''
# Select stock container services in private configuration mounts. Do not
# rewrite packaged init/service scripts or enable host hardware management.
cp /etc/rc.conf /run/rc.conf
printf '\nrc_sys="docker"\nrc_verbose=yes\nrc_nocolor=yes\n' >> /run/rc.conf
mount --bind /run/rc.conf /etc/rc.conf
cp -a /etc/conf.d /run/conf.d
printf '\nlog_dmesg=no\n' >> /run/conf.d/bootmisc
mount --bind /run/conf.d /etc/conf.d
mount -t tmpfs tmpfs /etc/runlevels
mkdir -p /etc/runlevels/sysinit /etc/runlevels/boot /etc/runlevels/default /etc/runlevels/shutdown
for service in procfs sysfs cgroups; do rc-update add "$service" sysinit; done
for service in bootmisc hostname; do rc-update add "$service" boot; done
for service in syslog crond local; do rc-update add "$service" default; done
mount -t tmpfs tmpfs /etc/local.d
mkdir -p /run/openrc
'''

RUNIT_PREPARE = r'''
# Scope only enablement links; package-owned service definitions must persist.
mkdir -p /run/md-runit
cp -a /etc/runit/runsvdir /run/md-runit/runsvdir
mount --bind /run/md-runit/runsvdir /etc/runit/runsvdir
mkdir -p /run/md-runit-services
'''

RUNIT_START = '''import errno, os, sys, threading, time
service = sys.argv[1]
deadline = time.monotonic() + 30
while True:
    try:
        fd = os.open(service + '/supervise/ok', os.O_WRONLY | os.O_NONBLOCK)
        os.close(fd)
        break
    except OSError as error:
        if error.errno not in (errno.ENOENT, errno.ENXIO):
            raise
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError('runsv did not acquire ' + service)
    # PROTOCOL_BACKOFF: wait for runsv's FIFO reader, not a presumed scan delay.
    threading.Event().wait(min(0.1, remaining))
os.execvp('sv', ['sv', '-w', '30', 'start', service])
'''


def enable_runit_service(service):
    name = shlex.quote(service)
    target = '/etc/runit/runsvdir/default/' + name
    return (f'if [ ! -L {target} ]; then\n'
            f'    for source in /etc/sv/{name} /etc/runit/sv/{name}; do\n'
            f'        if [ -f "$source/run" ]; then ln -s "$source" {target}; break; fi\n'
            '    done\n'
            'fi\n'
            f'test -f {target}/run')


def start_service(init, service):
    name = shlex.quote(service)
    if init == 'systemd':
        return 'systemctl start ' + name + '.service'
    if init == 'openrc':
        return 'rc-service ' + name + ' start'
    if init == 'runit':
        return (enable_runit_service(service) +
                ' && python3 -c ' + shlex.quote(RUNIT_START) +
                ' /etc/runit/runsvdir/default/' + name)
    raise ValueError('Unsupported init: ' + init)

def prepare_init():
    """Read the installer-owned preparation on the host, never on guest import."""
    return subprocess.check_output(['sh', str(Path(__file__).resolve().parents[2] /
        'scripts/install_linux.sh'), '--print-init-prepare'], text=True)


def stop_init_launch(suite, store, label):
    """Cancel only this fixture's registered tree, including detached services."""
    records = suite.command(suite.image_command('launches', store), quiet=True)
    owned = [item for line in records.splitlines() if line.strip()
             for item in [json.loads(line)] if item['label'] == label]
    assert len(owned) <= 1, owned
    for launch in owned:
        suite.command(suite.image_command('stop', store, launch['launchId']))


def installed_boot(target, port, token, init, desktop_user=None, system=None, control_address='127.0.0.1'):
    """One init/control owner; a desktop driver is an optional dependent service."""
    control_args = f'{target}/session-controller.py {target} {port} {token} {control_address}'
    binary = system + '/sw/bin' if system else '/usr/bin'
    bash = binary + '/bash' if system else '/bin/bash'
    services = [('control', binary + '/python3', control_args)]
    if desktop_user is not None:
        services.append(('desktop', bash, f'{target}/driver.sh'))
    boot = prepare_init() + '''
mount --make-rshared /
unset XDG_DATA_DIRS XDG_CONFIG_DIRS XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS
'''
    if desktop_user is not None:
        # System services do not inherit the graphical launcher's environment.
        # Carry only its audio endpoint, not arbitrary host environment variables.
        boot += 'printf "%s\\n" "${MAGICDESK_AUDIO_PULSE:-}" > ' + shlex.quote(target + '/audio-endpoint') + '\n'
    if system is not None:
        if init != 'systemd' or not system.startswith('/nix/store/'):
            raise ValueError('Expected an installed NixOS system generation')
    if control_address == '0.0.0.0':
        # A published test controller needs an input exception, not a disabled
        # distribution firewall. The launch owns this private network namespace.
        boot += f'''if command -v iptables >/dev/null 2>&1; then
    iptables -w 5 -I INPUT -i eth0 -p tcp --dport {port} -m comment --comment magicdesk-test-control -j ACCEPT
fi
'''
    user = shlex.quote(desktop_user) if desktop_user is not None else None
    driver = None
    if init == 'systemd':
        units = ' '.join('md-installed-' + name + '.service' for name, _, _ in services)
        boot += f'''mkdir -p /run/systemd/system
cat > /run/systemd/system/md-installed.target <<EOF
[Unit]
DefaultDependencies=no
Requires=dbus.socket {units}
After=dbus.socket {units}
EOF
'''
        for name, command, arguments in services:
            # Let systemd activate the packaged bus through dependencies. Some
            # distributions forbid manual starts of both its service and socket.
            prerequisites = ('Requires=basic.target dbus.service systemd-logind.service systemd-user-sessions.service\n'
                             'After=basic.target dbus.service systemd-logind.service systemd-user-sessions.service\n'
                             if name == 'desktop' else '')
            boot += f'''cat > /run/systemd/system/md-installed-{name}.service <<EOF
[Unit]
{prerequisites}Conflicts=shutdown.target
Before=shutdown.target
[Service]
Type=simple
ExecStart={command} {arguments}
StandardOutput=append:{target}/{name}.log
StandardError=inherit
EOF
'''
            if system is not None:
                boot += f'printf "%s\\n" "Environment=PATH={binary}:/usr/local/bin:/bin" >> /run/systemd/system/md-installed-{name}.service\n'
        systemd = system + '/systemd/lib/systemd/systemd' if system else '/usr/lib/systemd/systemd'
        boot += f'exec {binary}/setpriv --bounding-set=-sys_module {systemd} --system --unit=md-installed.target --show-status=no --crash-shell=no --crash-reboot=no\n'
        if user is not None:
            driver = f'''#!/bin/bash
set -eux
uid=$(id -u {user})
systemctl start user@$uid.service
exec systemd-run --unit=md-installed-desktop-session --wait --collect --uid={user} --property=PAMName=login --property=StandardOutput=append:{target}/driver.log --property=StandardError=inherit {bash} {target}/session.sh
'''
    elif init == 'openrc':
        boot += OPENRC_PREPARE + '''
cp -a /etc/init.d /run/md-init.d
mount --bind /run/md-init.d /etc/init.d
rc-update add dbus default
'''
        for name, command, arguments in services:
            boot += f'''cat > /etc/init.d/md-installed-{name} <<'EOF'
#!/sbin/openrc-run
command={command}
command_args="{arguments}"
command_background=yes
pidfile=/run/md-installed-{name}.pid
output_log={target}/{name}.log
error_log={target}/{name}.log
depend() {{ need dbus; }}
EOF
chmod 755 /etc/init.d/md-installed-{name}
rc-update add md-installed-{name} default
'''
        boot += 'exec /sbin/openrc-init\n'
    elif init == 'runit':
        boot += RUNIT_PREPARE
        boot += enable_runit_service('dbus') + '\n'
        for name, command, arguments in services:
            boot += f'''mkdir /run/md-runit-services/md-installed-{name}
cat > /run/md-runit-services/md-installed-{name}/run <<'EOF'
#!/bin/sh
exec >>{target}/{name}.log 2>&1
exec {command} {arguments}
EOF
chmod 755 /run/md-runit-services/md-installed-{name}/run
ln -s /run/md-runit-services/md-installed-{name} /etc/runit/runsvdir/default/md-installed-{name}
'''
        boot += 'exec runit\n'
    else:
        raise ValueError('Unsupported init: ' + init)
    if user is not None and init != 'systemd':
        driver = f'''#!/bin/bash
set -eux
{start_service(init, 'dbus')}
uid=$(id -u {user})
gid=$(id -g {user})
install -d -m 700 -o "$uid" -g "$gid" /run/user/$uid
exec setpriv --reuid="$uid" --regid="$gid" --init-groups /bin/bash {target}/session.sh
'''
    return boot, driver
