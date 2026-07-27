#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static void set_hid_scancode(
        struct input_keymap_entry *entry,
        unsigned int usage_page,
        unsigned int usage) {
    memset(entry, 0, sizeof(*entry));
    entry->len = 4;
    entry->scancode[0] = usage & 0xff;
    entry->scancode[1] = (usage >> 8) & 0xff;
    entry->scancode[2] = usage_page & 0xff;
    entry->scancode[3] = (usage_page >> 8) & 0xff;
}

static void set_button_two_scancode(struct input_keymap_entry *entry) {
    set_hid_scancode(entry, 0x09, 0x02);
}

static void set_tab_scancode(struct input_keymap_entry *entry) {
    set_hid_scancode(entry, 0x07, 0x2b);
}

static int handle_tab_mapping(const int fd, const char *target_name) {
    struct input_keymap_entry entry;
    set_tab_scancode(&entry);
    if (ioctl(fd, EVIOCGKEYCODE_V2, &entry) < 0) {
        perror("read Tab mapping");
        return 1;
    }
    const unsigned int previous = entry.keycode;
    if (strcmp(target_name, "tab-query") == 0) {
        printf("MAGICDESK_TAB_REMAP source-old=%u\n", previous);
        return 0;
    }

    unsigned int target;
    if (strcmp(target_name, "tab-filter") == 0) {
        target = KEY_UNKNOWN;
    } else if (strcmp(target_name, "tab-restore") == 0) {
        target = KEY_TAB;
    } else {
        fprintf(stderr, "unknown Tab mapping target\n");
        return 64;
    }
    if (previous != KEY_TAB && previous != KEY_UNKNOWN) {
        fprintf(stderr, "unexpected Tab mapping: %u\n", previous);
        return 1;
    }
    if (previous != target) {
        entry.keycode = target;
        if (ioctl(fd, EVIOCSKEYCODE_V2, &entry) < 0) {
            perror("write Tab mapping");
            return 1;
        }
    }

    set_tab_scancode(&entry);
    if (ioctl(fd, EVIOCGKEYCODE_V2, &entry) < 0) {
        perror("verify Tab mapping");
        return 1;
    }
    if (entry.keycode != target) {
        fprintf(stderr, "Tab mapping verification failed: %u\n", entry.keycode);
        return 1;
    }
    printf("MAGICDESK_TAB_REMAP_READY source-old=%u new=%u\n",
            previous, entry.keycode);
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s /dev/input/eventN "
                "unknown|right|tab-query|tab-filter|tab-restore\n", argv[0]);
        return 64;
    }

    const int fd = open(argv[1], O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        perror("open input device");
        return 1;
    }
    if (strncmp(argv[2], "tab-", 4) == 0) {
        const int result = handle_tab_mapping(fd, argv[2]);
        close(fd);
        return result;
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
