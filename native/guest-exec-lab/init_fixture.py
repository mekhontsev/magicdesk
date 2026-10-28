"""Private API filesystems and a real console for userspace init tests."""
import shlex

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

PREPARE = r'''set -eux
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
export container=shroot SYSTEMD_LOG_TARGET=console SYSTEMD_LOG_LEVEL=info
export SYSTEMD_LOG_COLOR=0 SYSTEMD_PAGER=cat LANG=C.UTF-8
test "$$" = 1
# Preserve the launch-owned input provider across the private /run mount.
udev_mount=
if mountpoint -q /run/udev; then
    udev_mount=$(mktemp -d /mnt/md-init-udev.XXXXXXXX)
    mount --move /run/udev "$udev_mount"
fi
mount -t tmpfs -o mode=755 tmpfs /run
if [ -n "$udev_mount" ]; then
    mkdir -p /run/udev
    mount --move "$udev_mount" /run/udev
    rmdir "$udev_mount"
fi
mount -t tmpfs -o mode=1777 tmpfs /tmp
mount -t tmpfs -o mode=1777 tmpfs /var/tmp
mount -t sysfs -o ro sysfs /sys
mount -t cgroup2 none /sys/fs/cgroup
console=$(tty)
mkdir -p /run/md-host-dev
# Retain mounted endpoints and their metadata, not just covered provider dentries.
mount --rbind /dev /run/md-host-dev
mount -t tmpfs -o mode=755 tmpfs /dev
mkdir -p /dev/pts /dev/shm
mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs /dev/shm
for device in null zero full random urandom tty; do
    touch /dev/$device
    mount --bind /run/md-host-dev/$device /dev/$device
done
# Keep launch-provided FUSE and graphics devices in every init scenario.
# Never expose arbitrary Android devices through the private /dev mount.
for node in fuse kgsl-3d0 dma_heap dri; do
    if [ -d /run/md-host-dev/$node ]; then mkdir -p /dev/$node
    elif [ -e /run/md-host-dev/$node ]; then touch /dev/$node
    else continue
    fi
    mount --bind /run/md-host-dev/$node /dev/$node
done
touch /dev/console
mount --bind "/run/md-host-dev/${console#/dev/}" /dev/console
mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts /dev/pts
ln -s pts/ptmx /dev/ptmx
ln -s /proc/self/fd /dev/fd
ln -s /proc/self/fd/0 /dev/stdin
ln -s /proc/self/fd/1 /dev/stdout
ln -s /proc/self/fd/2 /dev/stderr
umount -l /run/md-host-dev
rmdir /run/md-host-dev
'''


def installed_boot(target, port, token, init, desktop_user=None, system=None):
    """One init/control owner; a desktop driver is an optional dependent service."""
    control_args = f'{target}/session-controller.py {target} {port} {token}'
    binary = system + '/sw/bin' if system else '/usr/bin'
    bash = binary + '/bash' if system else '/bin/bash'
    services = [('control', binary + '/python3', control_args)]
    if desktop_user is not None:
        services.append(('desktop', bash, f'{target}/driver.sh'))
    boot = PREPARE + '''
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
        boot = boot.replace('export PATH=/usr/sbin:/usr/bin:/sbin:/bin',
                            'export PATH=' + shlex.quote(binary + ':/usr/local/bin:/bin'))
        boot = boot.replace('test "$$" = 1', 'test "$$" = 1\nmkdir -p /run /tmp /var/tmp')
        boot += shlex.quote(system + '/activate') + '\n'
    user = shlex.quote(desktop_user) if desktop_user is not None else None
    driver = None
    if init == 'systemd':
        # This retained fixture uses the launch's prepared uplink, not DHCP.
        # Keep service-triggered networkd startup from replacing that owner;
        # nested container links and default-target boot checks stay unchanged.
        boot += '''mkdir -p /run/systemd/network
cat > /run/systemd/network/00-md-fixture-uplink.network <<'EOF'
[Match]
Name=eth0
[Network]
DHCP=no
IPv6AcceptRA=no
LinkLocalAddressing=no
KeepConfiguration=static
EOF
'''
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
