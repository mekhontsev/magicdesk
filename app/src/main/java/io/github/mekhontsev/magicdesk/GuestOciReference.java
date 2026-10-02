package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.net.URI;

record GuestOciReference(String registry, String repository, String reference) {
    static GuestOciReference parse(String value) throws IOException {
        if (value == null || value.length() > 512 || value.contains("://"))
            throw new IOException("Expected registry/repository:tag or repository@sha256:digest");
        String name = value, reference = "latest";
        int at = value.indexOf('@');
        if (at >= 0) {
            name = value.substring(0, at); reference = value.substring(at + 1);
            GuestImageFiles.hex(reference);
        } else if (value.lastIndexOf(':') > value.lastIndexOf('/')) {
            int colon = value.lastIndexOf(':');
            name = value.substring(0, colon); reference = value.substring(colon + 1);
            if (!reference.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"))
                throw new IOException("Invalid image tag");
        }
        String registry = "registry-1.docker.io";
        int slash = name.indexOf('/');
        if (slash >= 0) {
            String first = name.substring(0, slash);
            if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
                registry = first; name = name.substring(slash + 1);
            }
        }
        if (registry.equals("docker.io") || registry.equals("index.docker.io")) registry = "registry-1.docker.io";
        if (!registry.matches("[a-zA-Z0-9][a-zA-Z0-9.-]*(?::[0-9]{1,5})?"))
            throw new IOException("Invalid registry host");
        URI endpoint = URI.create("https://" + registry);
        if (endpoint.getHost() == null || endpoint.getPort() > 65535) throw new IOException("Invalid registry address");
        if (registry.equals("registry-1.docker.io") && !name.contains("/")) name = "library/" + name;
        for (String part : name.split("/", -1)) {
            if (!part.matches("[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*"))
                throw new IOException("Invalid repository name");
        }
        return new GuestOciReference(registry, name, reference);
    }

    URI endpoint(String kind, String id) {
        return URI.create("https://" + registry + "/v2/" + repository + "/" + kind + "/" + id);
    }
}
