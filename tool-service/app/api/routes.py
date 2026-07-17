from fastapi import APIRouter, HTTPException, status

from app.core.models import AcceptedExecution, ToolExecutionRecord, ToolExecutionRequest
from app.execution.service import execution_service
from app.registry.registry import registry


router = APIRouter(prefix="/api/v1")


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@router.get("/tools")
def list_tools() -> list[dict]:
    return registry.manifests()


@router.post(
    "/tool-executions",
    response_model=AcceptedExecution,
    response_model_by_alias=True,
    status_code=status.HTTP_202_ACCEPTED,
)
def create_execution(request: ToolExecutionRequest) -> AcceptedExecution:
    try:
        record = execution_service.submit(request)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return AcceptedExecution(
        executionId=record.execution_id,
        status=record.status,
        statusUrl=f"/api/v1/tool-executions/{record.execution_id}",
    )


@router.get(
    "/tool-executions/{execution_id}",
    response_model=ToolExecutionRecord,
    response_model_by_alias=True,
)
def get_execution(execution_id: str) -> ToolExecutionRecord:
    record = execution_service.get(execution_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Tool execution not found")
    return record

