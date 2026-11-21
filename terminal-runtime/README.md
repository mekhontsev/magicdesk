# Android Terminal Runtime

Shared Android rendering, input encoding and native PTY framing for MagicDesk
and autonomous Linux applications. The parser and screen model remain in
`terminal-emulator`. This module does not own application windows, privileged
services, clipboard grants, environment selection or guest storage.

The native PTY relay remains an executable, not a JNI library. Its caller supplies
the working directory, executable and environment under its own Android UID.
