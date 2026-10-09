#!/usr/bin/env python3
"""Render the private LLM Nginx site from a strictly validated environment."""

from __future__ import annotations

import argparse
import ipaddress
import os
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping


PRIVATE_NETWORKS = tuple(
    ipaddress.ip_network(value)
    for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10")
)
PLACEHOLDERS = (
    "PRIVATE_LLM_LISTEN",
    "PRIVATE_LLM_SERVER_NAME",
    "PRIVATE_LLM_TLS_CERTIFICATE",
    "PRIVATE_LLM_TLS_CERTIFICATE_KEY",
    "PRIVATE_LLM_HTPASSWD",
    "PRIVATE_LLM_RATE",
    "PRIVATE_LLM_BURST",
    "PRIVATE_LLM_MAX_BODY_SIZE",
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

        listen_host, separator, listen_port = values["PRIVATE_LLM_LISTEN"].rpartition(":")
        if not separator or not listen_port.isdigit() or not 1 <= int(listen_port) <= 65535:
            raise ValueError("PRIVATE_LLM_LISTEN must be an IPv4 address and port, for example 100.64.0.10:8443")
        try:
            listen_address = ipaddress.ip_address(listen_host)
        except ValueError as error:
            raise ValueError("PRIVATE_LLM_LISTEN must use a literal private IPv4 address") from error
        if listen_address.version != 4 or not any(listen_address in network for network in PRIVATE_NETWORKS):
            raise ValueError("PRIVATE_LLM_LISTEN must be RFC1918 or Tailscale/CGNAT, never wildcard, loopback, or public")

        if not re.fullmatch(r"[A-Za-z0-9.-]+", values["PRIVATE_LLM_SERVER_NAME"]):
            raise ValueError("PRIVATE_LLM_SERVER_NAME contains unsupported characters")
        for name in ("PRIVATE_LLM_TLS_CERTIFICATE", "PRIVATE_LLM_TLS_CERTIFICATE_KEY", "PRIVATE_LLM_HTPASSWD"):
            path = Path(values[name])
            if not path.is_absolute() or any(character in values[name] for character in "\r\n;"):
                raise ValueError(f"{name} must be a safe absolute path")
        if not re.fullmatch(r"[1-9][0-9]*r/[sm]", values["PRIVATE_LLM_RATE"]):
            raise ValueError("PRIVATE_LLM_RATE must look like 4r/s or 60r/m")
        if not values["PRIVATE_LLM_BURST"].isdigit() or not 1 <= int(values["PRIVATE_LLM_BURST"]) <= 100:
            raise ValueError("PRIVATE_LLM_BURST must be between 1 and 100")
        if not re.fullmatch(r"[1-9][0-9]*[kKmM]", values["PRIVATE_LLM_MAX_BODY_SIZE"]):
            raise ValueError("PRIVATE_LLM_MAX_BODY_SIZE must be a non-zero Nginx size such as 256k")
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
    parser.add_argument("--template", type=Path, default=Path(__file__).parent / "nginx/private-llm.conf.template")
    parser.add_argument("--environment", type=Path, help="KEY=VALUE file; omit to read the current process environment")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check-only", action="store_true")
    arguments = parser.parse_args()

    environment = parse_environment_file(arguments.environment) if arguments.environment else os.environ
    settings = Settings.from_environment(environment)
    rendered = render(arguments.template.read_text(encoding="utf-8"), settings)
    if arguments.check_only:
        print("Private LLM Nginx configuration is valid.")
        return 0
    if arguments.output is None:
        parser.error("--output is required unless --check-only is used")
    write_atomic(arguments.output, rendered)
    print(arguments.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
