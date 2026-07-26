#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static void set_button_two_scancode(struct input_keymap_entry *entry) {
    memset(entry, 0, sizeof(*entry));
    entry->len = 4;
    entry->scancode[0] = 0x02;
    entry->scancode[1] = 0x00;
    entry->scancode[2] = 0x09;
    entry->scancode[3] = 0x00;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s /dev/input/eventN unknown|right\n", argv[0]);
        return 64;
    }

    unsigned int target;
    if (strcmp(argv[2], "unknown") == 0) {
        target = KEY_UNKNOWN;
    } else if (strcmp(argv[2], "right") == 0) {
        target = BTN_RIGHT;
    } else {
        fprintf(stderr, "target must be unknown or right\n");
        return 64;
    }

    const int fd = open(argv[1], O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        perror("open mouse");
        return 1;
    }

    struct input_keymap_entry entry;
    set_button_two_scancode(&entry);
    if (ioctl(fd, EVIOCGKEYCODE_V2, &entry) < 0) {
        perror("read button 2 mapping");
        close(fd);
        return 1;
    }
    const unsigned int previous = entry.keycode;
    if (previous != BTN_RIGHT && previous != BTN_EXTRA
            && previous != KEY_UNKNOWN) {
        fprintf(stderr, "unexpected button 2 mapping: %u\n", previous);
        close(fd);
        return 1;
    }

    if (previous != target) {
        entry.keycode = target;
        if (ioctl(fd, EVIOCSKEYCODE_V2, &entry) < 0) {
            perror("write button 2 mapping");
            close(fd);
            return 1;
        }
    }

    set_button_two_scancode(&entry);
    if (ioctl(fd, EVIOCGKEYCODE_V2, &entry) < 0) {
        perror("verify button 2 mapping");
        close(fd);
        return 1;
    }
    close(fd);
    if (entry.keycode != target) {
        fprintf(stderr, "button 2 verification failed: %u\n", entry.keycode);
        return 1;
    }

    printf("MAGICDESK_MOUSE_REMAP_READY source=%s old=%u new=%u\n",
            argv[1], previous, entry.keycode);
    return 0;
}
