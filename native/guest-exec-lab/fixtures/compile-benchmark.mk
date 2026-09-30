CC = gcc
CFLAGS = -O3 -flto -frandom-seed=magicdesk-benchmark -DSQLITE_THREADSAFE=1 -DSQLITE_ENABLE_FTS5 -DSQLITE_ENABLE_RTREE
LDLIBS = -lm -ldl -lpthread

all: sqlite3 libsqlite3.so verify-library

sqlite3: shell.o sqlite3.o
	$(CC) $(CFLAGS) -Wl,--build-id=none -o $@ $^ $(LDLIBS)

libsqlite3.so: sqlite3.pic.o
	$(CC) $(CFLAGS) -shared -Wl,--build-id=none,-soname,libsqlite3.so -o $@ $^ $(LDLIBS)

sqlite3.pic.o: sqlite3.c sqlite3.h
	$(CC) $(CFLAGS) -fPIC -c $< -o $@

verify-library: verify-library.c libsqlite3.so
	$(CC) -O2 -Wl,--build-id=none -o $@ $< -L. -lsqlite3

%.o: %.c sqlite3.h
	$(CC) $(CFLAGS) -c $< -o $@
