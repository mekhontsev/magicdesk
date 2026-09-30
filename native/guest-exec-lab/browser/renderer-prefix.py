#!/usr/bin/python3
"""Lab-only direct renderer: request Chromium's real layer-two sandbox.

The test browser/zygote is NOT confined. This separates the renderer's stock
seccomp policy from the unfinished layer-one filesystem authority. Never use
this launcher for browsing or report it as full browser sandbox support.
"""
import os
import sys


assert os.getuid() == os.geteuid() == 2000
arguments = sys.argv[1:]
assert arguments and "--type=renderer" in arguments
assert "--no-sandbox" in arguments
arguments = [arg for arg in arguments if arg != "--no-sandbox"]
assert not any(arg.startswith(("--disable-seccomp", "--disable-sandbox", "--no-zygote-sandbox"))
               for arg in arguments)
print("RENDERER_PROBE pid=" + str(os.getpid()) + " stock-seccomp-requested=true layer-one=false",
      file=sys.stderr, flush=True)
os.execv(arguments[0], arguments)
