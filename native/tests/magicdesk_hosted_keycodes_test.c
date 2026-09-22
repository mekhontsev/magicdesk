#include <assert.h>
#include "../../hosted-runtime/src/main/cpp/android_keycodes.h"

int main(void) {
    assert(hosted_evdev_keycode(29, 0) == KEY_A);
    assert(hosted_evdev_keycode(66, 0) == KEY_ENTER);
    assert(hosted_evdev_keycode(67, 0) == KEY_BACKSPACE);
    assert(hosted_evdev_keycode(113, 0) == KEY_LEFTCTRL);
    assert(hosted_evdev_keycode(142, 0) == KEY_F12);
    assert(hosted_evdev_keycode(29, KEY_Q) == KEY_Q);
    assert(hosted_evdev_keycode(29, KEY_MAX + 1) == KEY_A);
    assert(hosted_evdev_keycode(-1, 0) == 0);
    assert(hosted_evdev_keycode(304, 0) == 0);
    assert(hosted_evdev_keycode(0, 0) == 0);
    return 0;
}
