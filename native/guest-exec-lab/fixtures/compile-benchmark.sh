#!/bin/sh
set -eu
export PATH=/usr/bin:/bin HOME=/tmp TMPDIR=/tmp LC_ALL=C LANG=C TZ=UTC
export SOURCE_DATE_EPOCH=1750000000
cd /bench
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
        sha256sum sqlite3.c shell.c sqlite3.h /usr/bin/aarch64-linux-gnu-gcc-14
        sha256sum run.sh runtime-workloads.c
        exit 0
        ;;
    prepare)
        rm -f runtime-workloads
        gcc -O2 -UNDEBUG -Wall -Wextra -Werror -o runtime-workloads runtime-workloads.c
        ./runtime-workloads prepare
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
    make -B -j1 -f Makefile all
cat timing.txt
./sqlite3 --version
answer=$(./sqlite3 :memory: 'WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100000) SELECT sum(x) FROM n;')
test "$answer" = 5000050000
printf 'MD_SQL_OK %s\n' "$answer"
LD_LIBRARY_PATH=/bench ./verify-library
sha256sum sqlite3 libsqlite3.so shell.o sqlite3.o sqlite3.pic.o
