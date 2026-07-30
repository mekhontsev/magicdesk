package io.github.mekhontsev.magicdesk;

final class PackageNameValidator {
    private PackageNameValidator() {
    }

    static boolean isSafe(final String packageName) {
        if (packageName == null || packageName.isEmpty()
                || packageName.length() > 220) {
            return false;
        }
        for (int index = 0; index < packageName.length(); index++) {
            final char character = packageName.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '.') {
                continue;
            }
            return false;
        }
        return packageName.indexOf('.') > 0
                && packageName.indexOf("..") < 0;
    }
}
