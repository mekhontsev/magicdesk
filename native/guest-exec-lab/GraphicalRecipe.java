package io.github.mekhontsev.magicdesk;

/** Build-host fixture uses the actual app-side session policy, not a copied shell template. */
public final class GraphicalRecipe {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("One guest command required");
        System.out.print(LinuxGraphicalEnvironment.wrap(GraphicalProtocol.WAYLAND,
                LinuxGraphicalEnvironment.BusTransport.ABSTRACT, "/bin/sh -lc " + ShellCommandLine.quote(args[0])));
    }
}
