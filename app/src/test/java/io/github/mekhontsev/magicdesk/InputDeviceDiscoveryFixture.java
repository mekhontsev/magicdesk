package io.github.mekhontsev.magicdesk;

final class InputDeviceDiscoveryFixture {
    private InputDeviceDiscoveryFixture() {}

    // Reader IDs differ from EventHub IDs; equal names must not join devices.
    static final String TOUCHSCREENS = """
            Event Hub State:
              Devices:
                1: Touch panel
                  Classes: TOUCH | TOUCH_MT
                  Path: /dev/input/event1
                  Location: built-in-panel
                  Identifier: vendor=0x1234, product=0x0001
                21: Touch panel
                  Classes: TOUCH | TOUCH_MT | EXTERNAL
                  Path: /dev/input/event21
                  Location: usb-monitor
                  Identifier: vendor=0x1234, product=0x0002
                22: Monitor keys
                  Classes: KEYBOARD | ALPHAKEY | EXTERNAL
                  Path: /dev/input/event22
                  Location: usb-monitor
                  Identifier: vendor=0x1234, product=0x0002
                23: Single touch panel
                  Classes: TOUCH | EXTERNAL
                  Path: /dev/input/event23
                  Location: usb-single-touch
                  Identifier: vendor=0x1234, product=0x0003
                24: Legacy touchpad
                  Classes: TOUCH | TOUCH_MT | EXTERNAL
                  Path: /dev/input/event24
                  Location: usb-legacy-touchpad
                  Identifier: vendor=0x1234, product=0x0004
                25: Native touchpad
                  Classes: TOUCH | TOUCH_MT | TOUCHPAD | EXTERNAL
                  Path: /dev/input/event25
                  Location: usb-native-touchpad
                  Identifier: vendor=0x1234, product=0x0005
                26: Stylus digitizer
                  Classes: TOUCH | EXTERNAL_STYLUS
                  Path: /dev/input/event26
                  Location: stylus-digitizer
                  Identifier: vendor=0x1234, product=0x0006
                27: Virtual panel
                  Classes: TOUCH | EXTERNAL | VIRTUAL
                  Path: /dev/input/event27
                  Location: virtual-panel
                  Identifier: vendor=0x1234, product=0x0007
                28: Unknown touch device
                  Classes: TOUCH | EXTERNAL
                  Path: /dev/input/event28
                  Location: unknown-touch
                  Identifier: vendor=0x1234, product=0x0008
                29: Touch navigation pad
                  Classes: TOUCH | EXTERNAL
                  Path: /dev/input/event29
                  Location: navigation-pad
                  Identifier: vendor=0x1234, product=0x0009
            Input Reader State (Nums of device: 8):
              Device 101: Touch panel
                EventHub Devices: [ 1 ]
                IsExternal: false
                Touch Input Mapper (mode - DIRECT):
                  Parameters:
                    DeviceType: TOUCH_SCREEN
              Device 121: Touch panel
                EventHub Devices: [ 21 22 ]
                IsExternal: true
                Touch Input Mapper (mode - DISABLED):
                  Parameters:
                    DeviceType: TOUCH_SCREEN
              Device 123: Single touch panel
                EventHub Devices: [ 23 ]
                IsExternal: true
                Touch Input Mapper (mode - DIRECT):
                  Parameters:
                    DeviceType: TOUCH_SCREEN
              Device 124: Legacy touchpad
                EventHub Devices: [ 24 ]
                IsExternal: true
                Touch Input Mapper (mode - POINTER):
                  Parameters:
                    DeviceType: POINTER
              Device 125: Native touchpad
                EventHub Devices: [ 25 ]
                IsExternal: true
              Device 126: Stylus digitizer
                EventHub Devices: [ 26 ]
                IsExternal: false
                Touch Input Mapper (mode - DIRECT):
                  Parameters:
                    DeviceType: TOUCH_SCREEN
              Device 127: Virtual panel
                EventHub Devices: [ 27 ]
                IsExternal: true
                Touch Input Mapper (mode - DIRECT):
                  Parameters:
                    DeviceType: TOUCH_SCREEN
              Device 129: Touch navigation pad
                EventHub Devices: [ 29 ]
                IsExternal: true
                Touch Input Mapper (mode - NAVIGATION):
                  Parameters:
                    DeviceType: TOUCH_NAVIGATION
            Input Dispatcher State:
            """;
}
