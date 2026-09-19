from pathlib import Path

import pytest
import yaml

from paperagent_engine.product_gateway import ProductGateway

ROOT = Path(__file__).resolve().parents[2]


def test_compose_shares_tokens_and_persists_private_engine_state():
    config = yaml.safe_load((ROOT / "docker-compose.prod.yml").read_text(encoding="utf-8"))
    python = config["services"]["agent-engine-python"]
    api = config["services"]["api"]["environment"]
    env = python["environment"]
    assert python["profiles"] == ["python"]
    assert "ports" not in python
    assert "env_file" not in python
    assert env["PAPERAGENT_PYTHON_TOKEN"] == api["YANBAN_AGENT_PYTHON_SERVICE_TOKEN"]
    assert (
        env["PAPERAGENT_PYTHON_JAVA_SERVICE_TOKEN"]
        == api["YANBAN_AGENT_REACTPLAN_ENGINE_SERVICE_TOKEN"]
    )
    assert api["YANBAN_AGENT_PYTHON_ORIGIN"] == "http://agent-engine-python:8097"
    assert env["PAPERAGENT_PYTHON_JAVA_ORIGIN"] == "http://api:8080"
    assert python["volumes"] == ["python_engine_data:/var/lib/paperagent-python"]
    assert "python_engine_data" in config["volumes"]


@pytest.mark.parametrize(
    "origin",
    [
        "http://external.example:8080",
        "http://api:8081",
        "http://api:8080/path",
        "http://user@api:8080",
    ],
)
def test_gateway_rejects_non_allowlisted_origins(origin):
    with pytest.raises(ValueError):
        ProductGateway(origin, "synthetic-token")


def test_container_cli_uses_private_gateway_bind_and_persistent_directory(monkeypatch, tmp_path):
    import uvicorn

    from paperagent_engine import cli, product

    monkeypatch.setattr(cli, "load_local_environment", lambda: None)
    monkeypatch.setattr("sys.argv", ["paperagent-python", "serve-product"])
    for key, value in {
        "PAPERAGENT_PYTHON_HOST": "0.0.0.0",
        "PAPERAGENT_PYTHON_JAVA_ORIGIN": "http://api:8080",
        "PAPERAGENT_PYTHON_DATA_DIR": str(tmp_path),
        "PAPERAGENT_PYTHON_TOKEN": "synthetic-python-token-32-characters",
        "PAPERAGENT_PYTHON_JAVA_SERVICE_TOKEN": "synthetic-java-token",
    }.items():
        monkeypatch.setenv(key, value)

    def create(directory, token, gateway):
        assert directory == tmp_path
        assert gateway.origin == "http://api:8080"
        gateway.client.close()
        return "app"

    def run(app, **kwargs):
        assert app == "app"
        assert kwargs == {"host": "0.0.0.0", "port": 8097, "workers": 1, "access_log": False}

    monkeypatch.setattr(product, "create_product_app", create)
    monkeypatch.setattr(uvicorn, "run", run)
    cli.main()
