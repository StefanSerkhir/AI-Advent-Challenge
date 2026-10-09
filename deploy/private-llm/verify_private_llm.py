#!/usr/bin/env python3
"""Real-network verification for the authenticated private Ollama gateway.

Only Python's standard library is used. The script never prints or persists the
Basic Auth password. It deliberately targets a non-loopback HTTPS base URL.
"""

from __future__ import annotations

import base64
import concurrent.futures
import ipaddress
import json
import math
import os
import socket
import ssl
import statistics
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence


PRIVATE_NETWORKS = tuple(
    ipaddress.ip_network(value)
    for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "fc00::/7")
)


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: bytes
    headers: Mapping[str, str]
    elapsed_ms: float


@dataclass(frozen=True)
class Configuration:
    base_url: str
    username: str
    password: str
    model: str
    concurrency: int
    timeout_seconds: float
    rate_burst_requests: int
    rate_recovery_seconds: float
    context_probe_limit: int
    gateway_oversize_bytes: int
    network_evidence: str
    report_path: Path
    ssl_context: ssl.SSLContext

    @classmethod
    def from_environment(cls, environment: Mapping[str, str]) -> "Configuration":
        def required(name: str) -> str:
            value = environment.get(name, "").strip()
            if not value:
                raise ValueError(f"{name} is required")
            return value

        base_url = required("PRIVATE_LLM_BASE_URL").rstrip("/")
        username = required("PRIVATE_LLM_USERNAME")
        password = required("PRIVATE_LLM_PASSWORD")
        if ":" in username or any(character in username + password for character in "\r\n"):
            raise ValueError("Basic Auth username/password contain unsupported characters")
        concurrency = parse_integer(environment, "PRIVATE_LLM_CONCURRENCY", 4, minimum=4, maximum=32)
        rate_burst = parse_integer(environment, "PRIVATE_LLM_RATE_BURST_REQUESTS", 16, minimum=10, maximum=100)
        context_probe = parse_integer(environment, "PRIVATE_LLM_CONTEXT_PROBE_LIMIT", 4096, minimum=512, maximum=16384)
        gateway_oversize = parse_integer(environment, "PRIVATE_LLM_GATEWAY_OVERSIZE_BYTES", 270000, minimum=262145, maximum=1048576)
        timeout_seconds = parse_float(environment, "PRIVATE_LLM_TIMEOUT_SECONDS", 600.0, minimum=10.0, maximum=1800.0)
        rate_recovery = parse_float(environment, "PRIVATE_LLM_RATE_RECOVERY_SECONDS", 3.0, minimum=1.0, maximum=60.0)
        network_evidence = environment.get("PRIVATE_LLM_NETWORK_EVIDENCE", "same-host-private-address").strip()
        allowed_evidence = {"same-host-private-address", "container", "network-namespace", "separate-device"}
        if network_evidence not in allowed_evidence:
            raise ValueError(f"PRIVATE_LLM_NETWORK_EVIDENCE must be one of: {', '.join(sorted(allowed_evidence))}")

        ca_file = environment.get("PRIVATE_LLM_CA_FILE", "").strip()
        ssl_context = ssl.create_default_context(cafile=ca_file or None)
        validate_private_base_url(base_url)
        return cls(
            base_url=base_url,
            username=username,
            password=password,
            model=environment.get("PRIVATE_LLM_MODEL", "qwen3:14b").strip() or "qwen3:14b",
            concurrency=concurrency,
            timeout_seconds=timeout_seconds,
            rate_burst_requests=rate_burst,
            rate_recovery_seconds=rate_recovery,
            context_probe_limit=context_probe,
            gateway_oversize_bytes=gateway_oversize,
            network_evidence=network_evidence,
            report_path=Path(environment.get("PRIVATE_LLM_REPORT_PATH", "build/reports/private-llm-service.json")),
            ssl_context=ssl_context,
        )


def parse_integer(environment: Mapping[str, str], name: str, default: int, minimum: int, maximum: int) -> int:
    raw = environment.get(name, str(default))
    try:
        value = int(raw)
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def parse_float(environment: Mapping[str, str], name: str, default: float, minimum: float, maximum: float) -> float:
    raw = environment.get(name, str(default))
    try:
        value = float(raw)
    except ValueError as error:
        raise ValueError(f"{name} must be numeric") from error
    if not math.isfinite(value) or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def validate_private_base_url(base_url: str) -> list[str]:
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.query or parsed.fragment or parsed.username or parsed.password:
        raise ValueError("PRIVATE_LLM_BASE_URL must be an HTTPS origin without query or fragment")
    if parsed.path not in ("", "/"):
        raise ValueError("PRIVATE_LLM_BASE_URL must not contain a path")
    try:
        addresses = sorted({item[4][0] for item in socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)})
    except socket.gaierror as error:
        raise ValueError(f"PRIVATE_LLM_BASE_URL host cannot be resolved: {parsed.hostname}") from error
    if not addresses:
        raise ValueError("PRIVATE_LLM_BASE_URL did not resolve to an address")
    for raw_address in addresses:
        address = ipaddress.ip_address(raw_address)
        if address.is_loopback or not any(address in network for network in PRIVATE_NETWORKS):
            raise ValueError(f"PRIVATE_LLM_BASE_URL must resolve only to private/Tailscale addresses, got {address}")
    return addresses


def percentile(values: Sequence[float], percentage: float) -> float:
    if not values:
        raise ValueError("Cannot calculate a percentile of an empty sequence")
    ordered = sorted(values)
    index = max(0, math.ceil(percentage * len(ordered)) - 1)
    return ordered[index]


def find_context_length(model_info: Mapping[str, Any]) -> int | None:
    candidates = [value for key, value in model_info.items() if key.endswith(".context_length") and isinstance(value, int)]
    return max(candidates) if candidates else None


def find_runtime_context(processes: Mapping[str, Any], model: str) -> int | None:
    for entry in processes.get("models", []):
        if entry.get("name") == model or entry.get("model") == model:
            value = entry.get("context_length")
            return value if isinstance(value, int) else None
    return None


def basic_authorization(username: str, password: str) -> str:
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return f"Basic {token}"


def open_request(configuration: Configuration, request: urllib.request.Request):
    opener = urllib.request.build_opener(
        NoRedirectHandler(),
        urllib.request.HTTPSHandler(context=configuration.ssl_context),
    )
    return opener.open(request, timeout=configuration.timeout_seconds)


def http_request(
    configuration: Configuration,
    path: str,
    *,
    method: str = "GET",
    payload: Mapping[str, Any] | None = None,
    raw_payload: bytes | None = None,
    authenticated: bool = True,
) -> HttpResult:
    if payload is not None and raw_payload is not None:
        raise ValueError("payload and raw_payload are mutually exclusive")
    data = raw_payload if raw_payload is not None else (json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None)
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if authenticated:
        headers["Authorization"] = basic_authorization(configuration.username, configuration.password)
    request = urllib.request.Request(configuration.base_url + path, data=data, method=method, headers=headers)
    started = time.perf_counter()
    try:
        with open_request(configuration, request) as response:
            body = response.read()
            return HttpResult(response.status, body, dict(response.headers.items()), (time.perf_counter() - started) * 1000)
    except urllib.error.HTTPError as error:
        try:
            body = error.read()
            headers = dict(error.headers.items())
        finally:
            error.close()
        return HttpResult(error.code, body, headers, (time.perf_counter() - started) * 1000)


def parse_json(result: HttpResult, label: str) -> dict[str, Any]:
    try:
        value = json.loads(result.body)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise AssertionError(f"{label} did not return JSON (HTTP {result.status})") from error
    if not isinstance(value, dict):
        raise AssertionError(f"{label} returned a non-object JSON value")
    return value


def chat_payload(model: str, prompt: str, *, stream: bool, max_tokens: int) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": stream,
        "max_tokens": max_tokens,
        "reasoning_effort": "none",
        "temperature": 0,
    }
    if stream:
        payload["stream_options"] = {"include_usage": True}
    return payload


def non_streaming_chat(configuration: Configuration, prompt: str, max_tokens: int = 64) -> tuple[HttpResult, dict[str, Any], str]:
    result = http_request(
        configuration,
        "/v1/chat/completions",
        method="POST",
        payload=chat_payload(configuration.model, prompt, stream=False, max_tokens=max_tokens),
    )
    parsed = parse_json(result, "non-streaming chat") if result.status == 200 else {}
    choices = parsed.get("choices", [])
    content = choices[0].get("message", {}).get("content", "") if choices else ""
    return result, parsed, content.strip() if isinstance(content, str) else ""


def streaming_chat(configuration: Configuration, prompt: str) -> tuple[float, str, bool]:
    data = json.dumps(chat_payload(configuration.model, prompt, stream=True, max_tokens=64), ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        configuration.base_url + "/v1/chat/completions",
        data=data,
        method="POST",
        headers={
            "Accept": "text/event-stream",
            "Content-Type": "application/json",
            "Authorization": basic_authorization(configuration.username, configuration.password),
        },
    )
    started = time.perf_counter()
    fragments: list[str] = []
    done = False
    with open_request(configuration, request) as response:
        if response.status != 200:
            raise AssertionError(f"streaming chat returned HTTP {response.status}")
        for raw_line in response:
            line = raw_line.decode("utf-8").strip()
            if not line.startswith("data:"):
                continue
            data_line = line[5:].strip()
            if data_line == "[DONE]":
                done = True
                break
            event = json.loads(data_line)
            choices = event.get("choices", [])
            if choices:
                content = choices[0].get("delta", {}).get("content")
                if isinstance(content, str):
                    fragments.append(content)
    return (time.perf_counter() - started) * 1000, "".join(fragments).strip(), done


def context_prompt(approximate_tokens: int) -> str:
    # A repeated common ASCII token is intentionally closer to one tokenizer
    # token per word than unique numbered identifiers. It is still an estimate;
    # provider usage remains the observed value in the report.
    filler = "test " * approximate_tokens
    return (
        "BEGIN_CONTEXT_SENTINEL\n"
        + filler
        + "\nEND_CONTEXT_SENTINEL\nОтветь только: BEGIN_CONTEXT_SENTINEL END_CONTEXT_SENTINEL"
    )


def safe_json_summary(parsed: Mapping[str, Any]) -> dict[str, Any]:
    usage = parsed.get("usage") if isinstance(parsed.get("usage"), dict) else {}
    return {
        "model": parsed.get("model"),
        "promptTokens": usage.get("prompt_tokens"),
        "completionTokens": usage.get("completion_tokens"),
        "finishReason": (parsed.get("choices") or [{}])[0].get("finish_reason"),
    }


def write_report(path: Path, report: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}-", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def run_verification(configuration: Configuration) -> dict[str, Any]:
    resolved_addresses = validate_private_base_url(configuration.base_url)
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "status": "running",
        "baseUrl": configuration.base_url,
        "resolvedAddresses": resolved_addresses,
        "networkEvidence": configuration.network_evidence,
        "model": configuration.model,
        "checks": {},
    }
    checks: dict[str, Any] = report["checks"]

    unauthorized = http_request(configuration, "/private/health", authenticated=False)
    if unauthorized.status != 401:
        raise AssertionError(f"unauthenticated request must return 401, got {unauthorized.status}")
    checks["authentication"] = {"unauthenticatedStatus": unauthorized.status}

    version_result = http_request(configuration, "/private/ollama/version")
    tags_result = http_request(configuration, "/private/ollama/tags")
    show_result = http_request(configuration, "/private/ollama/show", method="POST", payload={"model": configuration.model, "verbose": False})
    for label, result in (("version", version_result), ("tags", tags_result), ("show", show_result)):
        if result.status != 200:
            raise AssertionError(f"Ollama {label} metadata returned HTTP {result.status}")
    version = parse_json(version_result, "Ollama version").get("version")
    tags = parse_json(tags_result, "Ollama tags")
    show = parse_json(show_result, "Ollama show")
    tag = next((entry for entry in tags.get("models", []) if entry.get("name") == configuration.model or entry.get("model") == configuration.model), None)
    if tag is None:
        raise AssertionError(f"model {configuration.model} is absent from /api/tags")
    metadata_context = find_context_length(show.get("model_info", {}))
    checks["versions"] = {
        "ollama": version,
        "model": configuration.model,
        "modelDigest": tag.get("digest"),
        "modelModifiedAt": tag.get("modified_at"),
        "parameterSize": (show.get("details") or {}).get("parameter_size"),
        "quantization": (show.get("details") or {}).get("quantization_level"),
        "metadataContextLength": metadata_context,
    }

    normal_result, normal_json, normal_content = non_streaming_chat(
        configuration,
        "Ответь одной строкой: PRIVATE-SERVICE-OK — локальная модель доступна.",
    )
    if normal_result.status != 200 or not normal_content:
        raise AssertionError(f"non-streaming chat failed: HTTP {normal_result.status}, empty={not normal_content}")
    if normal_json.get("model") != configuration.model:
        raise AssertionError(f"expected model {configuration.model}, got {normal_json.get('model')}")
    checks["nonStreaming"] = {
        "status": normal_result.status,
        "latencyMs": round(normal_result.elapsed_ms, 3),
        "responseCharacters": len(normal_content),
        **safe_json_summary(normal_json),
    }

    stream_elapsed, stream_content, stream_done = streaming_chat(
        configuration,
        "Начни ответ с STREAM-OK и добавь одно короткое предложение.",
    )
    if not stream_content or not stream_done:
        raise AssertionError(f"streaming chat did not finish correctly: done={stream_done}, empty={not stream_content}")
    checks["streaming"] = {
        "status": 200,
        "doneEvent": stream_done,
        "latencyMs": round(stream_elapsed, 3),
        "responseCharacters": len(stream_content),
    }

    # The successful non-streaming request above is also the model warm-up.
    time.sleep(configuration.rate_recovery_seconds)
    markers = [f"PARALLEL-{index + 1}-{uuid.uuid4().hex[:8]}" for index in range(configuration.concurrency)]

    def parallel_call(marker: str) -> dict[str, Any]:
        result, parsed, content = non_streaming_chat(
            configuration,
            f"Повтори маркер {marker} в начале ответа и затем напиши слово OK.",
            max_tokens=32,
        )
        foreign_markers = [candidate for candidate in markers if candidate != marker and candidate in content]
        return {
            "marker": marker,
            "status": result.status,
            "latencyMs": round(result.elapsed_ms, 3),
            "nonEmpty": bool(content),
            "ownMarkerPresent": marker in content,
            "foreignMarkers": foreign_markers,
            "model": parsed.get("model"),
        }

    with concurrent.futures.ThreadPoolExecutor(max_workers=configuration.concurrency) as executor:
        parallel_results = list(executor.map(parallel_call, markers))
    successful_parallel = [item for item in parallel_results if item["status"] == 200 and item["nonEmpty"] and item["ownMarkerPresent"] and not item["foreignMarkers"]]
    if len(successful_parallel) != configuration.concurrency:
        raise AssertionError(f"parallel verification succeeded for {len(successful_parallel)}/{configuration.concurrency} requests")
    parallel_latencies = [float(item["latencyMs"]) for item in parallel_results]
    checks["parallel"] = {
        "requested": configuration.concurrency,
        "successful": len(successful_parallel),
        "errors": configuration.concurrency - len(successful_parallel),
        "minLatencyMs": min(parallel_latencies),
        "maxLatencyMs": max(parallel_latencies),
        "p50LatencyMs": percentile(parallel_latencies, 0.50),
        "p95LatencyMs": percentile(parallel_latencies, 0.95),
        "meanLatencyMs": round(statistics.fmean(parallel_latencies), 3),
        "results": parallel_results,
    }

    ps_result = http_request(configuration, "/private/ollama/ps")
    if ps_result.status != 200:
        raise AssertionError(f"Ollama runtime metadata returned HTTP {ps_result.status}")
    runtime_context = find_runtime_context(parse_json(ps_result, "Ollama ps"), configuration.model)
    chosen_context_limit = min(value for value in (configuration.context_probe_limit, runtime_context or metadata_context) if value)
    below_tokens = max(256, math.floor(chosen_context_limit * 0.75))
    above_tokens = math.ceil(chosen_context_limit * 1.20)
    below_result, below_json, below_content = non_streaming_chat(configuration, context_prompt(below_tokens), max_tokens=32)
    if below_result.status != 200 or not below_content:
        raise AssertionError(f"below-context probe failed: HTTP {below_result.status}, empty={not below_content}")
    above_result, above_json, above_content = non_streaming_chat(configuration, context_prompt(above_tokens), max_tokens=32)
    above_usage = (above_json.get("usage") or {}).get("prompt_tokens") if above_json else None
    usage_suggests_truncation = isinstance(above_usage, int) and above_usage < math.floor(above_tokens * 0.90)
    if above_result.status == 200:
        context_behavior = (
            "accepted; provider usage suggests input truncation"
            if usage_suggests_truncation
            else "accepted; no deterministic truncation evidence in provider usage"
        )
    else:
        context_behavior = f"rejected with HTTP {above_result.status}"
    checks["context"] = {
        "metadataContextLength": metadata_context,
        "runtimeContextLength": runtime_context,
        "chosenSafeProbeLimit": chosen_context_limit,
        "below": {
            "approximateInputTokens": below_tokens,
            "status": below_result.status,
            "nonEmpty": bool(below_content),
            "beginSentinelPresent": "BEGIN_CONTEXT_SENTINEL" in below_content,
            "endSentinelPresent": "END_CONTEXT_SENTINEL" in below_content,
            **safe_json_summary(below_json),
        },
        "above": {
            "approximateInputTokens": above_tokens,
            "status": above_result.status,
            "nonEmpty": bool(above_content),
            "beginSentinelPresent": "BEGIN_CONTEXT_SENTINEL" in above_content,
            "endSentinelPresent": "END_CONTEXT_SENTINEL" in above_content,
            "reportedPromptTokens": above_usage,
            "usageSuggestsTruncation": usage_suggests_truncation,
            "observedBehavior": context_behavior,
            **safe_json_summary(above_json),
        },
        "maxTokens": 32,
    }

    # The oversized body is rejected by Nginx before Ollama parses or runs it.
    prefix = json.dumps(chat_payload(configuration.model, "", stream=False, max_tokens=1)).encode("utf-8")
    oversized = prefix[:-2] + (b"x" * configuration.gateway_oversize_bytes) + b'"}]}'
    oversized_result = http_request(
        configuration,
        "/v1/chat/completions",
        method="POST",
        raw_payload=oversized,
    )
    if oversized_result.status != 413:
        raise AssertionError(f"gateway oversized request must return 413, got {oversized_result.status}")
    checks["gatewayRequestLimit"] = {
        "advertised": normal_result.headers.get("X-Private-LLM-Request-Limit"),
        "sentBytes": len(oversized),
        "status": oversized_result.status,
    }

    time.sleep(configuration.rate_recovery_seconds)
    with concurrent.futures.ThreadPoolExecutor(max_workers=configuration.rate_burst_requests) as executor:
        burst_results = list(executor.map(lambda _: http_request(configuration, "/private/health"), range(configuration.rate_burst_requests)))
    burst_statuses = [item.status for item in burst_results]
    if 429 not in burst_statuses or 200 not in burst_statuses:
        raise AssertionError(f"rate-limit burst must contain both 200 and 429, got {sorted(set(burst_statuses))}")
    time.sleep(configuration.rate_recovery_seconds)
    recovered = http_request(configuration, "/private/health")
    if recovered.status != 200:
        raise AssertionError(f"request after rate-limit recovery returned HTTP {recovered.status}")
    checks["rateLimit"] = {
        "advertised": normal_result.headers.get("X-Private-LLM-Rate-Limit"),
        "burstRequests": configuration.rate_burst_requests,
        "successes": burst_statuses.count(200),
        "rejected429": burst_statuses.count(429),
        "otherStatuses": sorted({status for status in burst_statuses if status not in (200, 429)}),
        "recoveryWaitSeconds": configuration.rate_recovery_seconds,
        "afterWindowStatus": recovered.status,
    }

    report["status"] = "passed"
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    return report


def print_summary(report: Mapping[str, Any]) -> None:
    checks = report["checks"]
    parallel = checks["parallel"]
    context = checks["context"]
    rate = checks["rateLimit"]
    versions = checks["versions"]
    print(f"Private URL: {report['baseUrl']} ({', '.join(report['resolvedAddresses'])})")
    print(f"Network evidence: {report['networkEvidence']}")
    print(f"Ollama/model: {versions['ollama']} / {versions['model']} ({versions['modelDigest']})")
    print(f"Parallel: {parallel['successful']}/{parallel['requested']} success, errors={parallel['errors']}")
    print(
        "Latency ms: "
        f"min={parallel['minLatencyMs']:.3f}, max={parallel['maxLatencyMs']:.3f}, "
        f"p50={parallel['p50LatencyMs']:.3f}, p95={parallel['p95LatencyMs']:.3f}"
    )
    print(
        "Context: "
        f"metadata={context['metadataContextLength']}, runtime={context['runtimeContextLength']}, "
        f"safe-probe={context['chosenSafeProbeLimit']}, above={context['above']['observedBehavior']}"
    )
    print(
        "Rate limit: "
        f"{rate['advertised']}, HTTP 429={rate['rejected429']}, after-window={rate['afterWindowStatus']}"
    )


def main() -> int:
    configuration: Configuration | None = None
    report: dict[str, Any]
    try:
        configuration = Configuration.from_environment(os.environ)
        report = run_verification(configuration)
        print_summary(report)
        exit_code = 0
    except Exception as error:  # The report keeps a sanitized class/message, never credentials or bodies.
        report = {
            "schemaVersion": 1,
            "finishedAt": datetime.now(timezone.utc).isoformat(),
            "status": "failed",
            "errorType": type(error).__name__,
            "error": str(error),
        }
        print(f"Private LLM verification failed: {type(error).__name__}: {error}", file=sys.stderr)
        exit_code = 1
    report_path = configuration.report_path if configuration else Path(os.environ.get("PRIVATE_LLM_REPORT_PATH", "build/reports/private-llm-service.json"))
    write_report(report_path, report)
    print(f"Report: {report_path}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
