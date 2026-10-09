#!/usr/bin/env python3
"""Render the explicitly insecure, RFC1918-only LAN HTTP chat gateway."""

from __future__ import annotations

import argparse
import ipaddress
import os
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping


LAN_NETWORKS = tuple(
    ipaddress.ip_network(value)
    for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)
PLACEHOLDERS = (
    "LAN_LLM_LISTEN",
    "LAN_LLM_HTPASSWD",
    "LAN_LLM_RATE",
    "LAN_LLM_BURST",
    "LAN_LLM_MAX_BODY_SIZE",
)


@dataclass(frozen=True)
class Settings:
    values: Mapping[str, str]

    @classmethod
    def from_environment(cls, environment: Mapping[str, str]) -> "Settings":
        values = {name: environment.get(name, "").strip() for name in PLACEHOLDERS}
        missing = [name for name, value in values.items() if not value]
        if missing:
            raise ValueError(f"Missing required variables: {', '.join(missing)}")

        listen_host, separator, listen_port = values["LAN_LLM_LISTEN"].rpartition(":")
        if not separator or not listen_port.isdigit() or not 1024 <= int(listen_port) <= 65535:
            raise ValueError("LAN_LLM_LISTEN must be a private IPv4 address and unprivileged port")
        try:
            listen_address = ipaddress.ip_address(listen_host)
        except ValueError as error:
            raise ValueError("LAN_LLM_LISTEN must use a literal RFC1918 IPv4 address") from error
        if listen_address.version != 4 or not any(listen_address in network for network in LAN_NETWORKS):
            raise ValueError("LAN_LLM_LISTEN must be RFC1918, never wildcard, loopback, CGNAT, or public")

        htpasswd = Path(values["LAN_LLM_HTPASSWD"])
        if not htpasswd.is_absolute() or not re.fullmatch(r"/[A-Za-z0-9._/ -]+", values["LAN_LLM_HTPASSWD"]):
            raise ValueError("LAN_LLM_HTPASSWD must be a safe absolute path")
        if not re.fullmatch(r"[1-9][0-9]*r/[sm]", values["LAN_LLM_RATE"]):
            raise ValueError("LAN_LLM_RATE must look like 4r/s or 60r/m")
        if not values["LAN_LLM_BURST"].isdigit() or not 1 <= int(values["LAN_LLM_BURST"]) <= 100:
            raise ValueError("LAN_LLM_BURST must be between 1 and 100")
        if not re.fullmatch(r"[1-9][0-9]*[kKmM]", values["LAN_LLM_MAX_BODY_SIZE"]):
            raise ValueError("LAN_LLM_MAX_BODY_SIZE must be a non-zero Nginx size such as 256k")
        return cls(values)


def parse_environment_file(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        name, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise ValueError(f"Invalid environment assignment at {path}:{line_number}")
        result[name] = value.strip().strip('"')
    return result


def render(template: str, settings: Settings) -> str:
    output = template
    for name, value in settings.values.items():
        output = output.replace(f"@{name}@", value)
    unresolved = sorted(set(re.findall(r"@[A-Z0-9_]+@", output)))
    if unresolved:
        raise ValueError(f"Unresolved template placeholders: {', '.join(unresolved)}")
    return output


def write_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}-", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", type=Path, default=Path(__file__).parent / "nginx/lan-http-chat.conf.template")
    parser.add_argument("--environment", type=Path, help="KEY=VALUE file; omit to read the current process environment")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check-only", action="store_true")
    arguments = parser.parse_args()

    environment = parse_environment_file(arguments.environment) if arguments.environment else os.environ
    settings = Settings.from_environment(environment)
    rendered = render(arguments.template.read_text(encoding="utf-8"), settings)
    if arguments.check_only:
        print("Insecure LAN HTTP Nginx configuration is valid.")
        return 0
    if arguments.output is None:
        parser.error("--output is required unless --check-only is used")
    write_atomic(arguments.output, rendered)
    print(arguments.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
