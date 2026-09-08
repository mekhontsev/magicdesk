package io.github.mekhontsev.magicdesk;

import java.util.Objects;

/** Durable application reference. Package names alone do not identify an app. */
final class AppIdentity {
    final long profileSerialNumber;
    final String packageName;

    AppIdentity(final long profileSerialNumber, final String packageName) {
        if (profileSerialNumber < 0 || !PackageNameValidator.isSafe(packageName)) {
            throw new IllegalArgumentException("invalid application identity");
        }
        this.profileSerialNumber = profileSerialNumber;
        this.packageName = packageName;
    }

    String persistentKey() {
        return profileSerialNumber + "|" + packageName;
    }

    void requireProfile(final AppProfile profile) {
        if (profile == null || profile.serialNumber != profileSerialNumber) {
            throw new IllegalArgumentException(
                    "application belongs to an unavailable profile: "
                            + profileSerialNumber);
        }
    }

    static AppIdentity fromPersistentKey(final String key) {
        if (key == null) {
            throw new IllegalArgumentException("application identity is required");
        }
        final int separator = key.indexOf('|');
        if (separator <= 0) {
            throw new IllegalArgumentException("application profile is required");
        }
        final AppIdentity identity = new AppIdentity(
                Long.parseLong(key.substring(0, separator)),
                key.substring(separator + 1));
        if (!key.equals(identity.persistentKey())) {
            throw new IllegalArgumentException("non-canonical application identity");
        }
        return identity;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof AppIdentity
                && profileSerialNumber == ((AppIdentity) other).profileSerialNumber
                && packageName.equals(((AppIdentity) other).packageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Long.valueOf(profileSerialNumber), packageName);
    }
}
