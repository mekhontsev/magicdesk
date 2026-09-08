#include <assert.h>
#define main mouse_helper_main
#include "../magicdesk_uinput_bridge.c"
#undef main

static struct input_event next_event(const int fd, int type, int code, int value) {
    struct input_event event;
    assert(read(fd, &event, sizeof(event)) == sizeof(event));
    assert(event.type == type && event.code == code && event.value == value);
    return event;
}

int main(void) {
    int pipefd[2];
    assert(pipe(pipefd) == 0);
    struct bridge_state state = {.uinput_fd = pipefd[1]};
    assert(handle_control_line(&state, "move 12 -7") == 0);
    next_event(pipefd[0], EV_REL, REL_X, 12);
    next_event(pipefd[0], EV_REL, REL_Y, -7);
    next_event(pipefd[0], EV_SYN, SYN_REPORT, 0);
    assert(handle_control_line(&state, "primary-down") == 0);
    next_event(pipefd[0], EV_KEY, BTN_LEFT, 1);
    next_event(pipefd[0], EV_SYN, SYN_REPORT, 0);
    assert(handle_control_line(&state, "primary-down") == 0);
    assert(handle_control_line(&state, "click-primary") == 0);
    assert(handle_control_line(&state, "primary-up") == 0);
    next_event(pipefd[0], EV_KEY, BTN_LEFT, 0);
    next_event(pipefd[0], EV_SYN, SYN_REPORT, 0);
    assert(handle_control_line(&state, "click-secondary") == 0);
    next_event(pipefd[0], EV_KEY, BTN_RIGHT, 1);
    next_event(pipefd[0], EV_SYN, SYN_REPORT, 0);
    next_event(pipefd[0], EV_KEY, BTN_RIGHT, 0);
    next_event(pipefd[0], EV_SYN, SYN_REPORT, 0);
    assert(handle_control_line(&state, "scroll 2") == 0);
    next_event(pipefd[0], EV_REL, REL_WHEEL_HI_RES, 240);
    next_event(pipefd[0], EV_REL, REL_WHEEL, 2);
    next_event(pipefd[0], EV_SYN, SYN_REPORT, 0);
    assert(handle_control_line(&state, "scroll 2147483647") == -1);
    assert(handle_control_line(&state, "unexpected") == -1);
    assert(state.reports == 6 && state.write_errors == 0);
    close(pipefd[0]);
    close(pipefd[1]);
    puts("Virtual mouse: motion, drag, clicks, scroll and protocol verified");
    return 0;
}
