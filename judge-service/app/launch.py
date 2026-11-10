"""Wait for the parent to attach resource controls before starting a tool."""

import os
import subprocess
import sys


def main() -> int:
    if os.name == "nt":
        import msvcrt

        msvcrt.setmode(0, os.O_BINARY)
    if os.read(0, 1) != b"\x01":
        return 1

    address_space_mb = int(sys.argv[1])
    command = sys.argv[2:]
    if os.name != "nt":
        if address_space_mb:
            import resource

            limit = address_space_mb * 1024 * 1024
            resource.setrlimit(resource.RLIMIT_AS, (limit, limit))
        os.execv(command[0], command)
    return subprocess.call(command, shell=False)


if __name__ == "__main__":
    sys.exit(main())
