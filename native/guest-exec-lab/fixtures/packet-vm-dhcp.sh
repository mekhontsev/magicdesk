#!/bin/sh
set -eu
case "$1" in
    deconfig) ip -4 addr flush dev "$interface" ;;
    bound|renew)
        ip -4 addr flush dev "$interface"
        ip addr add "$ip/${mask:-24}" dev "$interface"
        for gateway in $router; do ip route replace default via "$gateway" dev "$interface"; break; done
        : > /etc/resolv.conf
        for address in $dns; do echo "nameserver $address" >> /etc/resolv.conf; done
        ;;
esac
