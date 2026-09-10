#!/usr/bin/env python3
"""A service that publishes must be connected to something.

    python scripts/verify_messaging.py

WHY THIS EXISTS

Assessment shipped to the dev cluster with `platform.outbox.enabled: true` and no
`platform.messaging` block at all. The outbox table was created, the relay ran on its schedule,
and every graded attempt was drained into `RecordingPublisher` -- which records a message and
delivers it nowhere.

Nothing failed. The pod was healthy, `NATS_URL` was set on it the whole time, and the only
evidence was one WARN at startup saying the variable nobody had mapped was unset. What it cost
downstream is the failure this whole mechanism exists to prevent: a learner passes a certification
exam, the gate behind it never opens, and no error is raised anywhere.

The same deploy turned up its twin -- `Streams.TOPOLOGY` missing the stream assessment publishes
into -- which `StreamsTest` now covers. This covers the other half, which no Java test can see:
platform-common cannot read another module's `application.yml`, and each service's own tests load
only its own.

WHAT IT CHECKS

For every service under `services/`:

  1. outbox enabled  =>  `platform.messaging.nats-url` is declared
  2. a MessageHandler =>  `platform.messaging.nats-url` is declared

Both are the same rule from the two ends: publishing into nothing and subscribing to nothing are
equally silent, and equally wrong.

It reads YAML by line rather than parsing it. These files carry long comment blocks that a
round-trip would not preserve, and the check needs one nested key -- not a document model.
"""
import io
import os
import re
import sys

SERVICES = "services"
CONFIG = os.path.join("src", "main", "resources", "config", "application.yml")
NATS_URL = "nats-url:"
OUTBOX_ON = re.compile(r"^\s{2,}outbox:\s*$")
ENABLED_TRUE = re.compile(r"^\s{2,}enabled:\s*true\s*$")


def config_of(service):
    path = os.path.join(SERVICES, service, CONFIG)
    return path if os.path.isfile(path) else None


def declares_nats_url(text):
    return any(line.strip().startswith(NATS_URL) for line in text.splitlines())


def outbox_is_enabled(text):
    """True when `outbox:` is followed by `enabled: true` before the next sibling key.

    Scanning forward rather than parsing, but not naively: `enabled: true` appears under several
    keys in these files, so only the run of lines belonging to the outbox block is considered.
    """
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if not OUTBOX_ON.match(line):
            continue
        indent = len(line) - len(line.lstrip())
        for following in lines[index + 1:]:
            if not following.strip() or following.lstrip().startswith("#"):
                continue
            if len(following) - len(following.lstrip()) <= indent:
                break  # out of the block
            if ENABLED_TRUE.match(following):
                return True
    return False


def has_a_handler(service):
    root = os.path.join(SERVICES, service, "src", "main", "java")
    for directory, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".java"):
                continue
            with io.open(os.path.join(directory, name), encoding="utf-8") as handle:
                if "implements MessageHandler" in handle.read():
                    return True
    return False


def main():
    if not os.path.isdir(SERVICES):
        print("Run this from the repository root.", file=sys.stderr)
        return 2

    failures = []
    print("Services that talk to the bus, and whether they are connected to one\n")

    for service in sorted(os.listdir(SERVICES)):
        path = config_of(service)
        if path is None:
            continue
        with io.open(path, encoding="utf-8") as handle:
            text = handle.read()

        publishes = outbox_is_enabled(text)
        subscribes = has_a_handler(service)
        if not publishes and not subscribes:
            continue

        connected = declares_nats_url(text)
        role = " and ".join(
            [word for word, yes in (("publishes", publishes), ("subscribes", subscribes)) if yes]
        )
        print("  %-6s %-11s %s" % ("ok" if connected else "FAIL", service, role))
        if not connected:
            failures.append(
                "%s %s but its application.yml never declares "
                "platform.messaging.nats-url, so RecordingPublisher is wired and every message "
                "is delivered nowhere." % (service, role)
            )

    print()
    if failures:
        print("=" * 66)
        for failure in failures:
            print("FAILED - " + failure)
        print("=" * 66)
        return 1

    print("=" * 66)
    print("PASSED - every service on the bus is configured to reach one.")
    print("=" * 66)
    return 0


if __name__ == "__main__":
    sys.exit(main())
