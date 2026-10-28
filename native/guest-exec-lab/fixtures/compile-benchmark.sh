#!/bin/sh
set -eu
export PATH=/usr/bin:/bin HOME=/tmp TMPDIR=/tmp LC_ALL=C LANG=C TZ=UTC
export SOURCE_DATE_EPOCH=1750000000
jobs=${2:-1}
iterations=${3:-1024}
blocks=${4:-8}
cd /bench
affinity() {
    ./runtime-workloads environment "$1"
}
if [ "${1:-build}" = prepare ]; then
    rm -f runtime-workloads
    gcc -O2 -UNDEBUG -Wall -Wextra -Werror -o runtime-workloads runtime-workloads.c
    gcc -O2 -UNDEBUG -Wall -Wextra -Werror -o readiness-benchmark readiness-benchmark.c
    ./runtime-workloads prepare
    exit 0
fi
affinity before
trap 'affinity after' EXIT
# Explicit inherited-selector scenario, never a side effect of measurement.
if [ "${5:-0}" = 1 ]; then
    IFS= read -r line < /proc/self/status
fi
case "${1:-build}" in
    probe)
        id
        while IFS= read -r line; do
            case "$line" in
                Uid:*|Gid:*|Seccomp:*|Seccomp_filters:*|Cpus_allowed_list:*) printf '%s\n' "$line" ;;
            esac
        done < /proc/self/status
        gcc --version
        make --version
        cat /etc/os-release
        sha256sum sqlite3.c shell.c sqlite3.h sqlite3ext.h Makefile verify-library.c
        printf 'MD_COMPILER %s\n' "$(sha256sum "$(readlink -f "$(command -v gcc)")")"
        printf 'MD_CC1 %s\n' "$(sha256sum "$(gcc -print-prog-name=cc1)")"
        sha256sum run.sh runtime-workloads.c readiness-benchmark.c
        exit 0
        ;;
    file-stat|file-open|file-cycle|poll|epoll|memory)
        case "$1" in poll|epoll|memory) blocks=1 ;; esac
        ./readiness-benchmark "$1" "$iterations" "$blocks"
        exit 0
        ;;
    metadata|spawn)
        rm -f timing.txt
        /usr/bin/time -f 'MD_TIMING %e %U %S %M %c %w' -o timing.txt ./runtime-workloads "$1"
        cat timing.txt
        exit 0
        ;;
    build) ;;
    *) exit 2 ;;
esac
rm -f sqlite3 libsqlite3.so verify-library shell.o sqlite3.o sqlite3.pic.o timing.txt
/usr/bin/time -f 'MD_TIMING %e %U %S %M %c %w' -o timing.txt \
    make -B -j"$jobs" -f Makefile all
cat timing.txt
./sqlite3 --version
answer=$(./sqlite3 :memory: 'WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100000) SELECT sum(x) FROM n;')
test "$answer" = 5000050000
printf 'MD_SQL_OK %s\n' "$answer"
LD_LIBRARY_PATH=/bench ./verify-library
sha256sum sqlite3 libsqlite3.so shell.o sqlite3.o sqlite3.pic.o
