import warnings

# Suppress Pydantic alias warnings on union types (known harmless with Pydantic 2.x)
warnings.filterwarnings("ignore", message=".*'alias' attribute.*was provided to the `Field`.*")

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app.api.routes import router
from app.core.config import settings
from app.execution.service import execution_service
from app.messaging.rabbit_worker import rabbit_worker


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.artifact_root.mkdir(parents=True, exist_ok=True)
    execution_service.start()
    rabbit_worker.start()
    yield
    rabbit_worker.stop()
    execution_service.shutdown()


app = FastAPI(
    title="Agent Video Pipeline Tool Service",
    version="0.1.0",
    lifespan=lifespan,
)
app.include_router(router)

@app.get("/metrics", include_in_schema=False)
def prometheus_metrics() -> Response:
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)
