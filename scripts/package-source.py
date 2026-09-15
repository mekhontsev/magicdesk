#!/usr/bin/env python3
"""Package a clean Git revision and all pinned submodule sources."""

import argparse
import gzip
import io
from pathlib import Path
import subprocess
import tarfile


def git(repository, *arguments):
    return subprocess.check_output(["git", "-C", str(repository), *arguments])


def source_repositories(repository, revision, prefix="MagicDesk/"):
    repository = repository.resolve()
    if Path(git(repository, "rev-parse", "--show-toplevel").decode().strip()).resolve() != repository:
        raise ValueError(f"Submodule is not initialized: {repository}")
    if git(repository, "status", "--porcelain", "--untracked-files=no").strip():
        raise ValueError(f"Refusing to package modified tracked sources: {repository}")
    commit = git(repository, "rev-parse", "--verify", f"{revision}^{{commit}}").decode().strip()
    if git(repository, "rev-parse", "HEAD").decode().strip() != commit:
        raise ValueError(f"Checkout does not match the source revision: {repository}")
    yield repository, commit, prefix
    for entry in git(repository, "ls-tree", "-rz", commit).split(b"\0"):
        if not entry:
            continue
        metadata, name = entry.split(b"\t", 1)
        mode, kind, object_id = metadata.split()
        if mode == b"160000" and kind == b"commit":
            path = name.decode("utf-8")
            yield from source_repositories(repository / path, object_id.decode(), prefix + path + "/")


def package_source(repository, destination):
    repositories = list(source_repositories(Path(repository), "HEAD"))
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    try:
        with temporary.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as zipped:
            with tarfile.open(fileobj=zipped, mode="w|") as archive:
                for source, revision, prefix in repositories:
                    with subprocess.Popen(["git", "-C", str(source), "archive", "--format=tar",
                                           f"--prefix={prefix}", revision], stdout=subprocess.PIPE) as process:
                        with tarfile.open(fileobj=process.stdout, mode="r|") as source_archive:
                            for member in source_archive:
                                archive.addfile(member, source_archive.extractfile(member) if member.isfile() else None)
                        if process.wait() != 0:
                            raise RuntimeError(f"Cannot archive {source}")
                revisions = "".join(f"{prefix} {revision}\n" for _, revision, prefix in repositories).encode()
                member = tarfile.TarInfo("MagicDesk/SOURCE_REVISIONS.txt")
                member.size = len(revisions)
                archive.addfile(member, io.BytesIO(revisions))
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    options = parser.parse_args()
    package_source(Path(__file__).resolve().parent.parent, options.destination)
