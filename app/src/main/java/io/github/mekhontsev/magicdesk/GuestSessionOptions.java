package io.github.mekhontsev.magicdesk;

import java.util.List;

/** A launch choice, not a property of an image, Android executor or display. */
record GuestSessionOptions(String user, boolean boot) {
    static final GuestSessionOptions DIRECT = new GuestSessionOptions("", false);

    GuestSessionOptions {
        user = user == null ? "" : user.trim();
        if (!user.isEmpty() && !user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\\$?"))
            throw new IllegalArgumentException("Invalid Linux user name");
    }

    static String words(List<String> argv) {
        return String.join(" ", argv.stream().map(ShellCommandLine::quote).toList());
    }

    // Resolve the image's default user in the original launch, before selecting guest root
    // to boot PID 1. Never infer that a blank User field means root.
    String invocation(GuestLaunchPlan plan, GuestLaunchPlan.Cli cli,
            String attachments, String prepare) {
        String launcher = cli.launcher();
        var env = plan.environment();
        var prefix = (boot && plan.command().isEmpty()
                ? new GuestLaunchPlan(env, plan.directory(), List.of("/bin/sh"), plan.magicDesk(), plan.network())
                : plan).launcherArguments(cli);
        var start = launcher + " " + words(prefix.subList(1, prefix.size()));
        if (!boot) return (prepare.isEmpty() ? "" : start + " -- /bin/sh -lc " + ShellCommandLine.quote(prepare) + "; ")
                + "exec " + start + attachments + " -- " + words(plan.command());
        String path = "export PATH=\"$PATH:/nix/var/nix/profiles/system/sw/bin\"; ";
        var probe = path + (prepare.isEmpty() ? "" : prepare + " && ")
                + "u=$(id -u) && g=$(id -g) && printf '%s:%s' \"$u\" \"$g\"";
        var root = new GuestLaunchPlan(new GuestEnvironment(env.store(), "", "0:0", true), "/",
                List.of("/bin/sh"), plan.magicDesk(), plan.network()).launcherArguments(cli);
        String check = path + "command -v magicdesk-init >/dev/null && magicdesk-init --help | grep -q -- --session || "
                + "{ echo 'Update magicdesk-init with install_linux.sh --resume before starting system services.' >&2; exit 1; }; "
                + "exec magicdesk-init --session \"$@\"";
        return "md_ids=$(" + start + " -- /bin/sh -c " + ShellCommandLine.quote(probe) + ") || exit $?; "
                + "exec " + launcher + " " + words(root.subList(1, root.size()))
                + (plan.network().mode() != GuestNetworkProfile.Mode.HOST ? ""
                : cli == GuestLaunchPlan.Cli.STANDALONE ? " --network bridge" : " --magicdesk-fallback-network bridge") + attachments
                + " -- /bin/sh -c " + ShellCommandLine.quote(check) + " sh \"$md_ids\" "
                + ShellCommandLine.quote(plan.directory()) + " " + words(plan.command());
    }
}
