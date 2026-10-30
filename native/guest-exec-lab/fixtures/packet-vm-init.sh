#!/bin/sh
export PATH=/usr/bin:/usr/sbin:/bin:/sbin
/usr/bin/busybox --install -s /usr/bin
set -ex
trap 'poweroff -f' EXIT
mount -t devtmpfs devtmpfs /dev
mount -t proc proc /proc
mount -t sysfs sysfs /sys
exec </dev/console >/dev/console 2>&1
/usr/sbin/modprobe virtio_net
/usr/sbin/modprobe virtio_mmio
ip link set lo up
ip link set eth0 up
ip addr add 198.51.100.2/24 dev eth0
echo 0 > /proc/sys/net/ipv6/conf/eth0/accept_dad
ip -6 addr add fd42:7::2/64 dev eth0
/packet-test
echo 'PASS packet VM completed'
poweroff -f
