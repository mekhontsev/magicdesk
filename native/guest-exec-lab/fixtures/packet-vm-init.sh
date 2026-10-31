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
echo 0 > /proc/sys/net/ipv6/conf/eth0/accept_dad
ip -6 addr add fd42:7::2/64 dev eth0
case "$(cat /proc/cmdline)" in
    *magicdesk.packet_dhcp=1*)
        udhcpc -f -n -q -t 5 -T 2 -i eth0 -s /dhcp.sh
        ip -4 addr show dev eth0 | grep '198.51.100.2/24'
        ip -4 route show default | grep 'via 198.51.100.1'
        grep 'nameserver 198.51.100.1' /etc/resolv.conf
        nslookup packet-peer.test | grep '198.51.101.1'
        ip -6 route replace default via fd42:7::1 dev eth0
        /packet-test 198.51.101.1 fd42:9::1
        ;;
    *)
        ip addr add 198.51.100.2/24 dev eth0
        /packet-test
        ;;
esac
echo 'PASS packet VM completed'
poweroff -f
