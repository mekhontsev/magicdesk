#define main bridge_main
#include "../magicdesk_pty_bridge.c"
#undef main
#include <assert.h>

int main(void) {
    int ready[2], lifetime[2];
    assert(pipe(ready) == 0 && pipe(lifetime) == 0);
    const int master = posix_openpt(O_RDWR | O_NOCTTY | O_NONBLOCK);
    assert(master >= 0 && grantpt(master) == 0 && unlockpt(master) == 0);
    char tty[64];
    assert(ptsname_r(master, tty, sizeof(tty)) == 0);
    const pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        close(master);
        close(ready[0]); close(lifetime[1]);
        assert(setsid() > 0);
        const int slave = open(tty, O_RDWR);
        assert(slave >= 0);
        struct termios raw;
        assert(tcgetattr(slave, &raw) == 0);
        cfmakeraw(&raw);
        assert(tcsetattr(slave, TCSANOW, &raw) == 0);
        assert(write(ready[1], "r", 1) == 1);
        char done;
        assert(read(lifetime[0], &done, 1) == 0);
        close(slave);
        _exit(0);
    }
    close(ready[1]); close(lifetime[0]);
    char notification;
    assert(read(ready[0], &notification, 1) == 1);
    close(ready[0]);
    struct process_relationship owner;
    assert(read_process_relationship(child, &owner) == 0 && owner.start_ticks > 0);
    unsigned char bytes[256], observed[256];
    for (size_t i = 0; i < sizeof(bytes); i++) bytes[i] = (unsigned char) i;
    size_t written;
    assert(emit_peer_output(child, owner.start_ticks, tty, bytes, sizeof(bytes), &written) == 0);
    assert(written == sizeof(bytes));
    struct pollfd readable = { .fd = master, .events = POLLIN };
    assert(poll(&readable, 1, 1000) == 1);
    assert(read(master, observed, sizeof(observed)) == sizeof(observed));
    assert(memcmp(bytes, observed, sizeof(bytes)) == 0);
    const int slave = open(tty, O_RDWR | O_NOCTTY | O_NONBLOCK);
    assert(slave >= 0);
    int input_bytes = -1;
    assert(ioctl(slave, FIONREAD, &input_bytes) == 0 && input_bytes == 0);
    close(slave);
    assert(emit_peer_output(child, owner.start_ticks + 1, tty, bytes, 1, &written) == ESTALE && written == 0);
    assert(emit_peer_output(child, owner.start_ticks, "/dev/null", bytes, 1, &written) == EINVAL && written == 0);

    unsigned char large[MAX_PEER_OUTPUT];
    memset(large, 'x', sizeof(large));
    const int64_t before = monotonic_millis();
    assert(emit_peer_output(child, owner.start_ticks, tty, large, sizeof(large), &written) == ETIMEDOUT);
    assert(written > 0 && written < sizeof(large));
    assert(monotonic_millis() - before < PEER_OUTPUT_TIMEOUT_MILLIS + 1000);
    close(lifetime[1]);
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(emit_peer_output(child, owner.start_ticks, tty, bytes, 1, &written) != 0 && written == 0);
    close(master);
    puts("PTY peer output: binary output, no input, stale identity and bounded partial writes verified");
    return 0;
}
