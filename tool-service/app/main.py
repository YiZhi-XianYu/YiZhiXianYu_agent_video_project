from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.core.config import settings
from app.execution.service import execution_service


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.artifact_root.mkdir(parents=True, exist_ok=True)
    yield
    execution_service.shutdown()


app = FastAPI(
    title="Agent Video Pipeline Tool Service",
    version="0.1.0",
    lifespan=lifespan,
)
app.include_router(router)

