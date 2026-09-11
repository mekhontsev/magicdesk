# Security Policy

## Reporting a Vulnerability

Use GitHub's [private vulnerability report](https://github.com/mekhontsev/magicdesk/security/advisories/new)
to report a suspected security issue in MagicDesk. You can also open the
repository's **Security** tab and choose **Report a vulnerability**.

Do not publish exploit details in public issues, pull requests, Reddit or
Telegram. The Telegram support bot is for ordinary troubleshooting, not
confidential security reports: its workflow can produce public test patches.
When unsure whether a problem is security-sensitive, use the private report.

Include what you have available:

- The exact MagicDesk version or commit and installation source.
- Android version, device and firmware, and the affected feature.
- Reproduction steps or a minimal proof of concept, expected behavior and
  observed behavior.
- The potential impact and any access or permissions needed to reproduce it.
- Relevant logs or a Diagnostics excerpt with private information removed.

For privilege or automation issues, include the selected startup backend,
actual service UID and force-shell setting, relevant MCP listener permissions, and whether access is local
or over a network. Never include live bearer tokens, passwords, signing keys
or unrelated personal files. Test only on devices and accounts you own or
have permission to use.

Follow-up discussion should stay in the private report. Please coordinate
public disclosure with the maintainer so users can receive a fix or mitigation.
Response and fix times depend on maintainer availability and the issue; there
is no guaranteed response deadline.

## Supported Versions

Security fixes target the latest stable release and current development code.
Older release lines do not receive separate security backports. Reports about
development builds are welcome; include their complete version or commit.
An older build should not prevent you from reporting a suspected vulnerability.

## Privilege Boundaries

MagicDesk can perform privileged operations through Shizuku or optional direct
root. The independent UID 2000 restriction does not revoke the application's
root-manager authorization. Authorized MCP
shell and input access deliberately allow broad device control; network MCP
requires a trusted network or protected tunnel. These permissions are not a
sandbox. See [the README security section](README.md#security) and
[privilege boundaries](docs/privilege-modes.md) for the intended behavior.
Suspected bypasses of authentication, permissions or those boundaries belong
in a private security report.

For ordinary crashes, display compatibility and feature requests, use
[GitHub issues](https://github.com/mekhontsev/magicdesk/issues) or
[community support](README.md#community-and-support).
