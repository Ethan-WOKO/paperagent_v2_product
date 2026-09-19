import argparse
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory

from dotenv import load_dotenv

from .contracts import Submission
from .models import DemoModel, LangChainModel
from .runtime import TERMINAL, Runtime


def load_local_environment():
    # Load only this service's file; explicit process variables take precedence.
    load_dotenv(
        Path(__file__).resolve().parents[2] / ".env",
        override=False,
        interpolate=False,
        encoding="utf-8-sig",
    )


def demo_submission():
    return Submission.model_validate(
        {
            "client_request_id": "synthetic-demo",
            "frame": {
                "objective": "Compare the synthetic study description with its results.",
                "objects": ["study", "results"],
                "deliverables": ["Evidence-backed findings"],
                "constraints": ["Read only; distinguish observations from conclusions"],
                "project_version": "synthetic-v1",
            },
            "documents": [
                {
                    "id": "study",
                    "text": "The synthetic study describes a sample of 100 participants.",
                },
                {"id": "results", "text": "The synthetic results table reports 90 participants."},
            ],
            "memories": [{"id": "preference", "content": "Prefer concise Chinese reports."}],
        }
    )


def main():
    parser = argparse.ArgumentParser(description="Isolated Python development engine")
    parser.add_argument("command", choices=["demo", "serve", "serve-product"])
    parser.add_argument("--model", choices=["demo", "openai"], default="demo")
    args = parser.parse_args()
    load_local_environment()
    # Never inherit ambient tracing of local documents into an external service.
    os.environ["LANGSMITH_TRACING"] = "false"
    os.environ["LANGCHAIN_TRACING_V2"] = "false"
    if args.command == "serve-product":
        import uvicorn

        from .product import create_product_app
        from .product_gateway import ProductGateway

        if args.model != "demo":
            parser.error(
                "serve-product always uses Java model routing; do not configure a direct provider"
            )
        for name in ("PAPERAGENT_PYTHON_TOKEN", "PAPERAGENT_PYTHON_JAVA_SERVICE_TOKEN"):
            if not os.environ.get(name, "").strip():
                parser.error(f"Set {name} in agent-engine-python/.env or the process environment")
        gateway = ProductGateway(
            os.environ.get("PAPERAGENT_PYTHON_JAVA_ORIGIN", "http://127.0.0.1:8080"),
            os.environ["PAPERAGENT_PYTHON_JAVA_SERVICE_TOKEN"],
        )
        host = os.environ.get("PAPERAGENT_PYTHON_HOST", "127.0.0.1")
        if host not in {"127.0.0.1", "0.0.0.0"}:
            parser.error("PAPERAGENT_PYTHON_HOST must be 127.0.0.1 or 0.0.0.0")
        app = create_product_app(
            Path(
                os.environ.get(
                    "PAPERAGENT_PYTHON_DATA_DIR",
                    str(Path(__file__).resolve().parents[2] / ".product-data"),
                )
            ),
            os.environ["PAPERAGENT_PYTHON_TOKEN"],
            gateway,
        )
        uvicorn.run(app, host=host, port=8097, workers=1, access_log=False)
        return
    model = DemoModel()
    if args.model == "openai":
        model = LangChainModel(
            os.environ["PAPERAGENT_PYTHON_MODEL"],
            os.environ["PAPERAGENT_PYTHON_MODEL_KEY"],
            os.environ.get("PAPERAGENT_PYTHON_MODEL_BASE_URL"),
        )
    if args.command == "demo":
        with TemporaryDirectory(prefix="paperagent-python-dev-") as tmp:
            runtime = Runtime(Path(tmp), model)
            try:
                view = runtime.submit(demo_submission())["task"]
                for _ in range(100):
                    if view["status"] in TERMINAL:
                        break
                    view = runtime.advance(view["task_id"])
                print(json.dumps(view, ensure_ascii=False, indent=2))
                if view["status"] != "succeeded":
                    raise SystemExit(1)
            finally:
                runtime.close()
    else:
        import uvicorn

        from .api import create_app

        # Fixed loopback port and local-only storage; no product env/config fallback.
        data_dir = Path(__file__).resolve().parents[2] / ".data"
        app = create_app(data_dir, os.environ["PAPERAGENT_PYTHON_TOKEN"], model)
        uvicorn.run(app, host="127.0.0.1", port=8097, workers=1, access_log=False)
