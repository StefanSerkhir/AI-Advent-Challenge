from __future__ import annotations

import importlib.util
import json
import os
import ssl
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DEPLOYMENT = ROOT / "deploy/private-llm"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


renderer = load_module("private_llm_renderer", DEPLOYMENT / "render_nginx_config.py")
verifier = load_module("private_llm_verifier", DEPLOYMENT / "verify_private_llm.py")


class RendererTest(unittest.TestCase):
    def setUp(self) -> None:
        self.environment = {
            "PRIVATE_LLM_LISTEN": "100.64.0.10:8443",
            "PRIVATE_LLM_SERVER_NAME": "llm.private.example",
            "PRIVATE_LLM_TLS_CERTIFICATE": "/etc/llm-workbench/tls/fullchain.pem",
            "PRIVATE_LLM_TLS_CERTIFICATE_KEY": "/etc/llm-workbench/tls/privkey.pem",
            "PRIVATE_LLM_HTPASSWD": "/etc/llm-workbench/gateway.htpasswd",
            "PRIVATE_LLM_RATE": "4r/s",
            "PRIVATE_LLM_BURST": "8",
            "PRIVATE_LLM_MAX_BODY_SIZE": "256k",
        }

    def test_renders_private_tls_authenticated_rate_limited_streaming_gateway(self) -> None:
        settings = renderer.Settings.from_environment(self.environment)
        template = (DEPLOYMENT / "nginx/private-llm.conf.template").read_text(encoding="utf-8")
        rendered = renderer.render(template, settings)

        self.assertNotIn("@PRIVATE_LLM_", rendered)
        self.assertIn("listen 100.64.0.10:8443 ssl;", rendered)
        self.assertIn("auth_basic_user_file /etc/llm-workbench/gateway.htpasswd;", rendered)
        self.assertIn("limit_req_status 429;", rendered)
        self.assertIn("proxy_buffering off;", rendered)
        self.assertIn("proxy_request_buffering off;", rendered)
        self.assertIn("server 127.0.0.1:11434;", rendered)
        self.assertIn("server 127.0.0.1:8080;", rendered)
        self.assertNotIn("0.0.0.0", rendered)

    def test_rejects_wildcard_loopback_and_public_listeners(self) -> None:
        for address in ("0.0.0.0:8443", "127.0.0.1:8443", "203.0.113.10:8443"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                renderer.Settings.from_environment({**self.environment, "PRIVATE_LLM_LISTEN": address})

    def test_environment_parser_and_atomic_write_do_not_require_secrets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            environment_file = directory_path / "gateway.env"
            environment_file.write_text("\n".join(f"{key}={value}" for key, value in self.environment.items()), encoding="utf-8")
            parsed = renderer.parse_environment_file(environment_file)
            settings = renderer.Settings.from_environment(parsed)
            rendered = renderer.render(
                (DEPLOYMENT / "nginx/private-llm.conf.template").read_text(encoding="utf-8"),
                settings,
            )
            output = directory_path / "private-llm.conf"
            renderer.write_atomic(output, rendered)
            self.assertEqual(rendered, output.read_text(encoding="utf-8"))
            self.assertNotIn("PASSWORD", rendered.upper())

    def test_systemd_units_preserve_loopback_and_external_state(self) -> None:
        ollama_drop_in = (DEPLOYMENT / "systemd/ollama.service.d/10-private-loopback.conf").read_text(encoding="utf-8")
        workbench_unit = (DEPLOYMENT / "systemd/llm-workbench.service").read_text(encoding="utf-8")
        workbench_environment = (DEPLOYMENT / "workbench.env.example").read_text(encoding="utf-8")

        self.assertIn('OLLAMA_HOST=127.0.0.1:11434', ollama_drop_in)
        self.assertIn('OLLAMA_CONTEXT_LENGTH=4096', ollama_drop_in)
        self.assertIn("WorkingDirectory=/var/lib/llm-workbench", workbench_unit)
        self.assertIn("ExecStart=/opt/llm-workbench/bin/AIAdventChallenge", workbench_unit)
        self.assertIn("WEB_PORT=8080", workbench_environment)
        self.assertNotIn("api_key", workbench_environment.lower())
        self.assertNotIn("0.0.0.0", ollama_drop_in + workbench_unit)


class VerifierLogicTest(unittest.TestCase):
    def test_embedded_credentials_are_rejected_before_resolution(self) -> None:
        with self.assertRaises(ValueError):
            verifier.validate_private_base_url("https://user:password@127.0.0.1:8443")

    def test_percentiles_use_nearest_rank(self) -> None:
        values = [10.0, 20.0, 30.0, 40.0]
        self.assertEqual(20.0, verifier.percentile(values, 0.50))
        self.assertEqual(40.0, verifier.percentile(values, 0.95))

    def test_context_metadata_and_runtime_are_distinct(self) -> None:
        metadata = {"qwen3.context_length": 40960, "qwen3.embedding_length": 5120}
        processes = {"models": [{"name": "qwen3:14b", "context_length": 4096}]}
        self.assertEqual(40960, verifier.find_context_length(metadata))
        self.assertEqual(4096, verifier.find_runtime_context(processes, "qwen3:14b"))

    def test_report_is_atomic_and_contains_no_credential(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            verifier.write_report(path, {"status": "passed", "baseUrl": "https://llm.private.example:8443"})
            content = path.read_text(encoding="utf-8")
            self.assertIn('"status": "passed"', content)
            self.assertNotIn("password", content.lower())


class VerifierIntegrationTest(unittest.TestCase):
    def test_full_verifier_contract_against_deterministic_fake_gateway(self) -> None:
        expected_authorization = verifier.basic_authorization("test-user", "test-password")

        class Handler(BaseHTTPRequestHandler):
            health_requests = 0
            health_lock = threading.Lock()

            def log_message(self, *_args) -> None:
                pass

            def send_json(self, status: int, value: dict) -> None:
                body = json.dumps(value).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("X-Private-LLM-Rate-Limit", "4r/s; burst=8")
                self.send_header("X-Private-LLM-Request-Limit", "256k")
                self.end_headers()
                self.wfile.write(body)

            def authenticated(self) -> bool:
                if self.headers.get("Authorization") == expected_authorization:
                    return True
                self.send_json(401, {"error": "authentication required"})
                return False

            def do_GET(self) -> None:
                if not self.authenticated():
                    return
                if self.path == "/private/health":
                    with self.health_lock:
                        type(self).health_requests += 1
                        request_number = type(self).health_requests
                    self.send_json(200 if request_number <= 3 or request_number > 10 else 429, {"version": "0.test"})
                elif self.path == "/private/ollama/version":
                    self.send_json(200, {"version": "0.test"})
                elif self.path == "/private/ollama/tags":
                    self.send_json(200, {"models": [{"name": "qwen3:14b", "digest": "sha256:test", "modified_at": "2026-10-08T00:00:00Z"}]})
                elif self.path == "/private/ollama/ps":
                    self.send_json(200, {"models": [{"name": "qwen3:14b", "context_length": 4096}]})
                else:
                    self.send_json(404, {"error": "not found"})

            def do_POST(self) -> None:
                if not self.authenticated():
                    return
                content_length = int(self.headers.get("Content-Length", "0"))
                if content_length > 262144:
                    self.send_json(413, {"error": "too large"})
                    return
                payload = json.loads(self.rfile.read(content_length) or b"{}")
                if self.path == "/private/ollama/show":
                    self.send_json(200, {
                        "details": {"parameter_size": "14.8B", "quantization_level": "Q4_K_M"},
                        "model_info": {"qwen3.context_length": 40960},
                    })
                    return
                if self.path != "/v1/chat/completions":
                    self.send_json(404, {"error": "not found"})
                    return
                prompt = payload.get("messages", [{}])[-1].get("content", "")
                if payload.get("stream"):
                    body = (
                        'data: {"choices":[{"delta":{"content":"STREAM-OK fake"}}]}\n\n'
                        "data: [DONE]\n\n"
                    ).encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                marker = next((word for word in prompt.split() if word.startswith("PARALLEL-")), None)
                content = f"{marker} OK" if marker else (
                    "BEGIN_CONTEXT_SENTINEL END_CONTEXT_SENTINEL"
                    if "END_CONTEXT_SENTINEL" in prompt else "PRIVATE-SERVICE-OK"
                )
                self.send_json(200, {
                    "model": "qwen3:14b",
                    "choices": [{"message": {"content": content}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": len(prompt.split()), "completion_tokens": 4},
                })

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        original_validator = verifier.validate_private_base_url
        verifier.validate_private_base_url = lambda _base_url: ["10.0.0.42"]
        try:
            configuration = verifier.Configuration(
                base_url=f"http://127.0.0.1:{server.server_port}",
                username="test-user",
                password="test-password",
                model="qwen3:14b",
                concurrency=4,
                timeout_seconds=5,
                rate_burst_requests=10,
                rate_recovery_seconds=0.01,
                context_probe_limit=512,
                gateway_oversize_bytes=262145,
                network_evidence="network-namespace",
                report_path=Path("unused.json"),
                ssl_context=ssl.create_default_context(),
            )
            report = verifier.run_verification(configuration)
        finally:
            verifier.validate_private_base_url = original_validator
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual("passed", report["status"])
        self.assertEqual(4, report["checks"]["parallel"]["successful"])
        self.assertEqual(7, report["checks"]["rateLimit"]["rejected429"])
        self.assertEqual(200, report["checks"]["rateLimit"]["afterWindowStatus"])
        self.assertEqual(40960, report["checks"]["context"]["metadataContextLength"])
        self.assertEqual(4096, report["checks"]["context"]["runtimeContextLength"])


if __name__ == "__main__":
    unittest.main()
