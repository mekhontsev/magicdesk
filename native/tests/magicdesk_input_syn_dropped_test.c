/* Host-only: real bridge/source code, controlled EVIOCGKEY and uinput writes. */
#include <assert.h>
#include <errno.h>
#include <linux/input.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define FIXTURE_WORD_BITS (sizeof(unsigned long) * 8U)
#define FIXTURE_WORDS (KEY_MAX / FIXTURE_WORD_BITS + 1U)
#define FIXTURE_SOURCE_BASE 100
#define FIXTURE_UINPUT 200

static unsigned long kernel_keys[2][FIXTURE_WORDS];
static unsigned int query_count[2];
static bool fail_query;
static struct input_event emitted[128];
static size_t emitted_count;
static char protocol[4096];
static size_t protocol_length;
static size_t live_snapshots;

static void *fixture_malloc(size_t size) {
    void *value = malloc(size);
    if (value != NULL) live_snapshots++;
    return value;
}

static void fixture_free(void *value) {
    if (value != NULL) {
        assert(live_snapshots > 0);
        live_snapshots--;
    }
    free(value);
}

static int fixture_ioctl(int fd, unsigned long request, ...) {
    assert(fd >= FIXTURE_SOURCE_BASE && fd < FIXTURE_SOURCE_BASE + 2);
    assert(request == EVIOCGKEY(sizeof(kernel_keys[0])));
    query_count[fd - FIXTURE_SOURCE_BASE]++;
    if (fail_query) {
        errno = EIO;
        return -1;
    }
    va_list arguments;
    va_start(arguments, request);
    void *keys = va_arg(arguments, void *);
    va_end(arguments);
    memcpy(keys, kernel_keys[fd - FIXTURE_SOURCE_BASE], sizeof(kernel_keys[0]));
    return 0;
}

static ssize_t fixture_write(int fd, const void *data, size_t length) {
    assert(fd == FIXTURE_UINPUT && length == sizeof(struct input_event));
    assert(emitted_count < sizeof(emitted) / sizeof(emitted[0]));
    memcpy(&emitted[emitted_count++], data, length);
    return (ssize_t) length;
}

static int fixture_printf(const char *format, ...) {
    va_list arguments;
    va_start(arguments, format);
    const int count = vsnprintf(protocol + protocol_length,
            sizeof(protocol) - protocol_length, format, arguments);
    va_end(arguments);
    assert(count >= 0 && (size_t) count < sizeof(protocol) - protocol_length);
    protocol_length += (size_t) count;
    return count;
}

#define ioctl fixture_ioctl
#define write fixture_write
#define printf fixture_printf
#define malloc fixture_malloc
#define free fixture_free
#define main magicdesk_input_bridge_main
#ifdef FIXTURE_KEYBOARD
#include "../magicdesk_keyboard_bridge.c"
#else
#include "../magicdesk_uinput_bridge.c"
#endif
#undef main
#include "../magicdesk_input_sources.c"
#undef ioctl
#undef write
#undef printf
#undef malloc
#undef free

#define CHECK(condition, message) do { \
    if (!(condition)) { \
        fprintf(stderr, "FAIL: %s\n", message); \
        return 1; \
    } \
} while (0)

static struct source_device sources[2];
static struct bridge_state state;
#ifdef FIXTURE_KEYBOARD
static int uinput_fd = FIXTURE_UINPUT;
static const unsigned short primary = KEY_A;
static const unsigned short other = KEY_B;
#else
static const unsigned short primary = BTN_LEFT;
static const unsigned short other = BTN_MIDDLE;
#endif

static int deliver(int source, unsigned short type, unsigned short code, int value) {
    const struct input_event event = {.type = type, .code = code, .value = value};
#ifdef FIXTURE_KEYBOARD
    return state.paused ? queue_event(&state, source, &event)
            : process_event(&state, source, &event);
#else
    return process_event(&state, source, &event);
#endif
}

static size_t emitted_key_count(unsigned short code, int value) {
    size_t count = 0;
    for (size_t i = 0; i < emitted_count; i++) {
        if (emitted[i].type == EV_KEY && emitted[i].code == code
                && emitted[i].value == value) {
            count++;
        }
    }
    return count;
}

static void snapshot_down(int source, unsigned short code) {
    kernel_keys[source][code / FIXTURE_WORD_BITS] |= 1UL << (code % FIXTURE_WORD_BITS);
}

static int lost_release(void) {
    assert(deliver(0, EV_KEY, primary, 1) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    const size_t before = emitted_count;
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(state.key_down_count[primary] == 0 && !state.forwarded_down[primary]
            && !sources[0].key_down[primary], "lost release left key/button down");
    CHECK(query_count[0] == 1 && query_count[1] == 0,
            "recovery must query only the dropped source once at SYN_REPORT");
    CHECK(emitted_key_count(primary, 0) == 1, "missing neutralizing key/button release");
    for (size_t i = before; i < emitted_count; i++) {
        CHECK(emitted[i].type != EV_SYN || emitted[i].code != SYN_DROPPED,
                "source SYN_DROPPED leaked into virtual device");
    }
    return 0;
}

static int multiple_sources(void) {
    assert(deliver(0, EV_KEY, primary, 1) == 0);
    assert(deliver(1, EV_KEY, primary, 1) == 0);
    assert(deliver(1, EV_KEY, other, 1) == 0);
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(1, EV_KEY, other, 0) == 0);
    assert(deliver(1, EV_KEY, other, 1) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(state.key_down_count[primary] == 1 && sources[1].key_down[primary]
            && state.forwarded_down[primary], "recovery lost shared key/button ownership");
    CHECK(state.key_down_count[other] == 1 && state.forwarded_down[other]
            && sources[1].key_down[other], "recovery cleared an unrelated source");
    CHECK(emitted_key_count(other, 1) == 2, "one dropped source suppressed another source's input");
    CHECK(emitted_key_count(primary, 0) == 0, "shared key/button released too early");
    assert(deliver(1, EV_KEY, primary, 0) == 0);
    CHECK(state.key_down_count[primary] == 0 && emitted_key_count(primary, 0) == 1,
            "last source did not release the shared key/button");
    return 0;
}

static int discard_until_report(void) {
    assert(deliver(0, EV_KEY, primary, 1) == 0);
    snapshot_down(0, primary);
    const size_t before = emitted_count;
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_KEY, other, 1) == 0);
    assert(deliver(0, EV_KEY, primary, 0) == 0);
#ifndef FIXTURE_KEYBOARD
    assert(deliver(0, EV_REL, REL_X, 91) == 0);
#endif
    CHECK(query_count[0] == 0 && emitted_count == before,
            "tainted events before SYN_REPORT were forwarded or queried early");
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(state.key_down_count[primary] == 1 && sources[0].key_down[primary]
            && state.forwarded_down[primary], "authoritative held state was released");
    CHECK(state.key_down_count[other] == 0, "tainted press became source state");
    CHECK(query_count[0] == 1, "SYN_REPORT did not query authoritative state");
    return 0;
}

static int failed_snapshot(void) {
    assert(deliver(0, EV_KEY, primary, 1) == 0);
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    fail_query = true;
    CHECK(deliver(0, EV_SYN, SYN_REPORT, 0) < 0, "failed ioctl was treated as a valid state");
    CHECK(state.key_down_count[primary] == 1 && state.forwarded_down[primary],
            "unavailable snapshot was fabricated as neutral");
    return 0;
}

static int missing_press(void) {
    snapshot_down(0, primary);
#ifdef FIXTURE_KEYBOARD
    snapshot_down(0, KEY_LEFTMETA);
    snapshot_down(0, KEY_L);
#else
    snapshot_down(0, BTN_RIGHT);
#endif
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(state.key_down_count[primary] == 1 && state.forwarded_down[primary]
            && sources[0].key_down[primary], "observed held key/button was not restored");
    CHECK(emitted_key_count(primary, 1) == 1, "restored state did not reach virtual device");
    CHECK(protocol_length == 0, "state snapshot generated a MagicDesk shortcut/click");
    memset(kernel_keys, 0, sizeof(kernel_keys));
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(state.key_down_count[primary] == 0 && emitted_key_count(primary, 0) == 1,
            "restored hold could not be neutralized by a subsequent snapshot");
    CHECK(protocol_length == 0, "neutralization generated a MagicDesk shortcut/click");
    return 0;
}

#ifdef FIXTURE_KEYBOARD
static int queue_cleanup(void) {
    state.paused = true;
    for (size_t i = 0; i < MAX_QUEUED_EVENTS; i++) {
        assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
        assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    }
    CHECK(live_snapshots == MAX_QUEUED_EVENTS, "queued snapshots lack bounded ownership");
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    CHECK(deliver(0, EV_SYN, SYN_REPORT, 0) < 0, "snapshot queue exceeded its existing cap");
    CHECK(live_snapshots == MAX_QUEUED_EVENTS, "overflow leaked a new snapshot");
    assert(clear_input_state(&state) == 0);
    CHECK(live_snapshots == 0 && state.queue_count == 0, "input reset leaked queued snapshots");
    return 0;
}

static int paused_recovery(void) {
    state.paused = true;
    assert(deliver(0, EV_KEY, primary, 1) == 0);
    assert(deliver(1, EV_KEY, other, 1) == 0);
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(query_count[0] == 1, "paused recovery did not capture state at SYN_REPORT");
    CHECK(emitted_count == 0, "paused recovery bypassed the layout queue");
    snapshot_down(0, primary);
    assert(deliver(0, EV_KEY, primary, 1) == 0);
    state.paused = false;
    assert(drain_queue(&state) == 0);
    CHECK(query_count[0] == 1, "queue replay queried a later kernel state");
    CHECK(emitted_key_count(primary, 1) == 2 && emitted_key_count(primary, 0) == 1,
            "paused snapshot lost its position before the later press");
    CHECK(state.key_down_count[primary] == 1 && state.key_down_count[other] == 1,
            "paused recovery changed another source or lost subsequent input");
    return 0;
}

static int shortcuts(void) {
    assert(deliver(0, EV_KEY, KEY_LEFTALT, 1) == 0);
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(!state.modifier_pending[KEY_LEFTALT], "lost Alt release left a pending modifier");
    CHECK(emitted_key_count(KEY_LEFTALT, 1) == 0, "recovery fabricated a deferred Alt tap");
    assert(deliver(0, EV_KEY, KEY_LEFTALT, 1) == 0);
    assert(deliver(1, EV_KEY, KEY_LEFTALT, 1) == 0);
    assert(deliver(0, EV_KEY, KEY_TAB, 1) == 0);
    assert(state.alt_tab_active);
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(state.alt_tab_active, "recovery committed Alt+Tab owned by another source");
    CHECK(!sources[0].consumed[KEY_TAB] && state.key_down_count[KEY_TAB] == 0,
            "recovery left a consumed shortcut key down");
    assert(deliver(1, EV_KEY, KEY_LEFTALT, 0) == 0);
    CHECK(!state.alt_tab_active, "last Alt release did not commit Alt+Tab");
    return 0;
}
#else
static int shortcuts(void) {
    assert(set_control_primary(&state, true) == 0);
    assert(deliver(0, EV_KEY, BTN_LEFT, 1) == 0);
    assert(deliver(0, EV_KEY, BTN_RIGHT, 1) == 0);
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(state.control_primary_down && !state.forwarded_down[BTN_LEFT]
            && state.key_down_count[BTN_LEFT] == 0, "recovery lost control/physical ownership split");
    CHECK(emitted_key_count(BTN_LEFT, 0) == 0, "recovery released a control-owned button");
    CHECK(emitted_key_count(BTN_RIGHT, 1) == 1
            && emitted_key_count(BTN_RIGHT, 0) == 1,
            "lost secondary release was not forwarded");
    CHECK(protocol_length == 0, "lost physical release generated a shell command");
    assert(set_control_primary(&state, false) == 0);
    CHECK(emitted_key_count(BTN_LEFT, 0) == 1, "control release did not release its button");
    assert(deliver(0, EV_KEY, BTN_RIGHT, 1) == 0);
    assert(deliver(0, EV_KEY, BTN_RIGHT, 0) == 0);
    CHECK(emitted_key_count(BTN_RIGHT, 1) == 2
            && emitted_key_count(BTN_RIGHT, 0) == 2,
            "normal secondary click no longer works after recovery");
    return 0;
}

static int secondary_clicks(void) {
    assert(deliver(0, EV_KEY, BTN_RIGHT, 1) == 0);
    assert(deliver(1, EV_KEY, BTN_RIGHT, 1) == 0);
    assert(handle_control_line(&state, "click-secondary") == 0);
    CHECK(protocol_length == 0 && emitted_key_count(BTN_RIGHT, 1) == 1
            && emitted_key_count(BTN_RIGHT, 0) == 0,
            "control click interrupted a physical hold");
    assert(deliver(0, EV_KEY, BTN_RIGHT, 0) == 0);
    CHECK(protocol_length == 0 && emitted_key_count(BTN_RIGHT, 0) == 0,
            "secondary button released before its last owner");
    assert(deliver(1, EV_KEY, BTN_RIGHT, 0) == 0);
    CHECK(emitted_key_count(BTN_RIGHT, 1) == 1
            && emitted_key_count(BTN_RIGHT, 0) == 1,
            "physical secondary button did not preserve its full sequence");
    CHECK(protocol_length == 0, "physical secondary button generated a shell command");

    emitted_count = protocol_length = 0;
    protocol[0] = '\0';
    assert(handle_control_line(&state, "click-secondary") == 0);
    CHECK(emitted_key_count(BTN_RIGHT, 1) == 1
            && emitted_key_count(BTN_RIGHT, 0) == 1,
            "touchpad secondary click did not reach the virtual device");
    CHECK(emitted_count == 4 && emitted[0].type == EV_KEY
            && emitted[0].code == BTN_RIGHT && emitted[0].value == 1
            && emitted[1].type == EV_SYN && emitted[1].code == SYN_REPORT
            && emitted[2].type == EV_KEY && emitted[2].code == BTN_RIGHT
            && emitted[2].value == 0
            && emitted[3].type == EV_SYN && emitted[3].code == SYN_REPORT,
            "touchpad secondary click lost report boundaries");
    CHECK(protocol_length == 0, "touchpad secondary click generated a shell command");

    emitted_count = protocol_length = 0;
    protocol[0] = '\0';
    snapshot_down(0, BTN_RIGHT);
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    memset(kernel_keys, 0, sizeof(kernel_keys));
    assert(deliver(0, EV_SYN, SYN_DROPPED, 0) == 0);
    assert(deliver(0, EV_SYN, SYN_REPORT, 0) == 0);
    CHECK(emitted_key_count(BTN_RIGHT, 1) == 1
            && emitted_key_count(BTN_RIGHT, 0) == 1,
            "secondary-button recovery did not restore native state");
    CHECK(protocol_length == 0, "recovery generated a shell command");

    emitted_count = 0;
    assert(deliver(0, EV_KEY, BTN_RIGHT, 1) == 0);
    assert(deliver(1, EV_KEY, BTN_RIGHT, 1) == 0);
    assert(clear_button_state(&state) == 0);
    CHECK(state.key_down_count[BTN_RIGHT] == 0 && !state.forwarded_down[BTN_RIGHT]
            && !sources[0].key_down[BTN_RIGHT] && !sources[1].key_down[BTN_RIGHT],
            "source reset retained secondary-button ownership");
    CHECK(emitted_key_count(BTN_RIGHT, 1) == 1
            && emitted_key_count(BTN_RIGHT, 0) == 1,
            "source reset did not release the secondary button exactly once");
    assert(handle_control_line(&state, "click-secondary") == 0);
    CHECK(emitted_key_count(BTN_RIGHT, 1) == 2
            && emitted_key_count(BTN_RIGHT, 0) == 2,
            "touchpad secondary click remained blocked after source reset");
    return 0;
}
#endif

int main(int argc, char **argv) {
    assert(argc == 2);
    (void) fixture_malloc;
    for (int i = 0; i < 2; i++) {
        sources[i].fd = FIXTURE_SOURCE_BASE + i;
        sources[i].grabbed = true;
    }
    state.sources = sources;
    state.source_count = 2;
#ifdef FIXTURE_KEYBOARD
    state.uinput_fds = &uinput_fd;
    state.layout_count = 1;
    state.started = true;
#else
    state.uinput_fd = FIXTURE_UINPUT;
    state.capture_enabled = true;
#endif
    int result;
    if (strcmp(argv[1], "lost") == 0) result = lost_release();
    else if (strcmp(argv[1], "multiple") == 0) result = multiple_sources();
    else if (strcmp(argv[1], "discard") == 0) result = discard_until_report();
    else if (strcmp(argv[1], "failure") == 0) result = failed_snapshot();
    else if (strcmp(argv[1], "held") == 0) result = missing_press();
    else if (strcmp(argv[1], "shortcuts") == 0) result = shortcuts();
#ifdef FIXTURE_KEYBOARD
    else if (strcmp(argv[1], "paused") == 0) result = paused_recovery();
    else if (strcmp(argv[1], "queue-cleanup") == 0) result = queue_cleanup();
#else
    else if (strcmp(argv[1], "secondary-native") == 0) result = secondary_clicks();
#endif
    else return 2;
    if (result == 0) {
#ifdef FIXTURE_KEYBOARD
        assert(clear_input_state(&state) == 0);
#else
        assert(clear_button_state(&state) == 0);
#endif
        CHECK(live_snapshots == 0, "snapshot ownership leaked after completion");
        printf("PASS: %s\n", argv[1]);
    }
    return result;
}
