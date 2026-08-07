package io.github.mekhontsev.magicdesk;

abstract class DesktopInputDevice {
    final String path;
    final String location;
    final int vendorId;
    final int productId;

    DesktopInputDevice(
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

final class DesktopMouseDevice extends DesktopInputDevice {
    DesktopMouseDevice(
            final String path,
            final String location,
            final int vendorId,
            final int productId) {
        super(path, location, vendorId, productId);
    }
}

final class DesktopKeyboardDevice extends DesktopInputDevice {
    DesktopKeyboardDevice(
            final String path,
            final String location,
            final int vendorId,
            final int productId) {
        super(path, location, vendorId, productId);
    }
}
