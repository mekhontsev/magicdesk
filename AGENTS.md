# Repository Instructions

## Releases

When the user asks to publish a MagicDesk release, treat the release tag and
the next development cycle as one task.

1. Verify that the tag matches `magicDeskVersionName` in `gradle.properties`.
2. Build, test, tag, push, and verify the signed GitHub release.
3. Choose the next development version with the user if it was not already
   specified.
4. Run `scripts/start-next-version.sh VERSION VERSION_CODE`, commit the version
   change, and push it.

Do not report the release task complete while the version on `main` still
matches the newest release tag. Do not increment the version for ordinary
pushes; CI adds a unique development-build suffix automatically.
