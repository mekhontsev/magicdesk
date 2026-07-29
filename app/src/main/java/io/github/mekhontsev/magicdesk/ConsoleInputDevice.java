package io.github.mekhontsev.magicdesk;

abstract class ConsoleInputDevice {
    final String path;
    final String location;
    final int vendorId;
    final int productId;
    int inputDeviceId = -1;
    boolean remapped;

    ConsoleInputDevice(
            final String path,
            final String location,
            final int vendorId,
            final int productId) {
        this.path = path;
        this.location = location;
        this.vendorId = vendorId;
        this.productId = productId;
    }
}

final class ConsoleMouseDevice extends ConsoleInputDevice {
    ConsoleMouseDevice(
            final String path,
            final String location,
            final int vendorId,
            final int productId) {
        super(path, location, vendorId, productId);
    }
}

final class ConsoleKeyboardDevice extends ConsoleInputDevice {
    long tabDownTime;

    ConsoleKeyboardDevice(
            final String path,
            final String location,
            final int vendorId,
            final int productId) {
        super(path, location, vendorId, productId);
    }
}
