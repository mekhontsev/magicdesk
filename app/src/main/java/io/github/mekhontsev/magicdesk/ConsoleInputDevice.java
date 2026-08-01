package io.github.mekhontsev.magicdesk;

abstract class ConsoleInputDevice {
    final String path;
    final String location;
    final int vendorId;
    final int productId;

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
    ConsoleKeyboardDevice(
            final String path,
            final String location,
            final int vendorId,
            final int productId) {
        super(path, location, vendorId, productId);
    }
}
