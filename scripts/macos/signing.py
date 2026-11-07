#!/usr/bin/env python3
"""Resolve and validate the Developer ID identity installed on this Mac."""
import re
import subprocess
import sys


def identities():
    result = subprocess.run(
        ["security", "find-identity", "-v", "-p", "codesigning"],
        check=True, capture_output=True, text=True,
    )
    found = {}
    for line in result.stdout.splitlines():
        match = re.search(r'"(Developer ID Application: .+ \(([A-Z0-9]{10})\))"$', line)
        if match:
            found[match.group(1)] = match.group(2)
    return found


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "select":
        available = identities()
        if not available:
            print("-", end="")
        elif len(available) == 1:
            print(next(iter(available)))
        else:
            raise SystemExit("Multiple Developer ID identities are installed; set SOFATAP_SIGN_IDENTITY to one exact identity.")
        return

    if len(sys.argv) == 3 and sys.argv[1] == "team":
        identity = sys.argv[2]
        team = identities().get(identity)
        if not team:
            raise SystemExit("The requested Developer ID identity is not currently valid in this Mac's Keychain.")
        print(team)
        return

    raise SystemExit("Usage: signing.py select | signing.py team 'Developer ID Application: Name (TEAMID)'")


if __name__ == "__main__":
    main()
