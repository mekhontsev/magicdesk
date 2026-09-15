# Licensing and Source Availability

MagicDesk is distributed under the GNU General Public License, version 3
only (`GPL-3.0-only`); the complete terms are in [LICENSE](../LICENSE).
Copyright (c) 2026 Dmitry Mekhontsev and contributors.

Files carrying a separate compatible license retain that license and their
copyright notices. In particular, the MIT notice applying to the original
MagicDesk code is preserved in [LICENSES/MIT-MagicDesk.txt](../LICENSES/MIT-MagicDesk.txt).
Previously granted MIT permissions are not revoked. Third-party component
licenses are listed in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Binary Distribution

Every distributed APK must have its corresponding source available to its
recipients, including modifications, pinned submodules and build scripts.
Release and development CI attach a source archive alongside the APK.
The archive is produced from the exact checked-out commit with:

```sh
python scripts/package-source.py dist/MagicDesk-source.tar.gz
```

Initialize submodules recursively before building or packaging. The source
archive includes their contents, not just Git links. It excludes Git metadata,
untracked files, local credentials, signing keys and build output. Recipients
can build and sign their own APK; our private signing key is not included.

Personalized test APKs have the same source-availability requirement. A support
bot must deliver the matching source archive or a durable link to it together
with the binary. Linking only to the changing default branch is insufficient.
Private, undistributed experiments do not need public publication.

## Embedded X11

The MagicDesk X11 fork preserves Termux:X11's GPLv3 license, upstream history
and component notices. Keeping its sources in a separate repository does not
change the licensing of the combined application. Its exact revision and
nested sources must accompany APKs that include it.
