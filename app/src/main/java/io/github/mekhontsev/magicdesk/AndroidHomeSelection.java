package io.github.mekhontsev.magicdesk;

/** Static snapshot of the HOME application selected before a desktop lease. */
final class AndroidHomeSelection {
    enum Availability {
        NONE,
        UNRESOLVED,
        UNAVAILABLE,
        DECLARED
    }

    final String packageName;
    final String componentName;
    final long packageVersionCode;
    final Availability availability;

    private AndroidHomeSelection(
            final String packageName,
            final String componentName,
            final long packageVersionCode,
            final Availability availability) {
        this.packageName = value(packageName);
        this.componentName = value(componentName);
        this.packageVersionCode = packageVersionCode;
        this.availability = availability;
        validate();
    }

    static AndroidHomeSelection none() {
        return new AndroidHomeSelection(
                "", null, -1, Availability.NONE);
    }

    static AndroidHomeSelection unresolved(final String packageName) {
        return new AndroidHomeSelection(
                packageName, null, -1, Availability.UNRESOLVED);
    }

    static AndroidHomeSelection fromResolution(
            final String selectedPackage,
            final AndroidActivityResolution resolution) {
        if (selectedPackage == null || selectedPackage.isEmpty()) {
            return none();
        }
        if (resolution == null
                || resolution.state != AndroidActivityResolution.CONCRETE
                || resolution.component == null
                || !selectedPackage.equals(
                        resolution.component.getPackageName())) {
            return unresolved(selectedPackage);
        }
        final Availability availability = resolution.authorization.allowed()
                ? Availability.DECLARED : Availability.UNAVAILABLE;
        return new AndroidHomeSelection(
                selectedPackage,
                resolution.component.flattenToShortString(),
                resolution.packageVersionCode,
                availability);
    }

    static AndroidHomeSelection fromPersisted(
            final String packageName,
            final String flattenedComponent,
            final long packageVersionCode,
            final String availabilityName) {
        return new AndroidHomeSelection(
                packageName,
                flattenedComponent,
                packageVersionCode,
                Availability.valueOf(value(availabilityName)));
    }

    String availabilityName() {
        return availability.name().toLowerCase(java.util.Locale.ROOT);
    }

    private void validate() {
        if (availability == null
                || packageVersionCode < -1
                || (!packageName.isEmpty()
                        && !PackageNameValidator.isSafe(packageName))) {
            throw new IllegalArgumentException("invalid HOME selection");
        }
        if (availability == Availability.NONE) {
            if (!packageName.isEmpty()
                    || !componentName.isEmpty()
                    || packageVersionCode != -1) {
                throw new IllegalArgumentException("invalid empty HOME selection");
            }
            return;
        }
        if (packageName.isEmpty()) {
            throw new IllegalArgumentException("HOME package is required");
        }
        if (availability == Availability.UNRESOLVED) {
            if (!componentName.isEmpty() || packageVersionCode != -1) {
                throw new IllegalArgumentException(
                        "unresolved HOME cannot have Activity metadata");
            }
            return;
        }
        if (!packageName.equals(componentPackage(componentName))) {
            throw new IllegalArgumentException(
                    "HOME component must belong to its package");
        }
    }

    private static String value(final String source) {
        return source == null ? "" : source;
    }

    private static String componentPackage(final String flattened) {
        final String value = value(flattened);
        final int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1
                || value.indexOf('/', separator + 1) >= 0) {
            return "";
        }
        return value.substring(0, separator);
    }
}
