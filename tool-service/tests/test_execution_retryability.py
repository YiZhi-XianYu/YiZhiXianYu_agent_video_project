from app.execution.service import ExecutionService


def test_contract_errors_are_not_retryable() -> None:
    assert ExecutionService._is_retryable(ValueError("bad parameters")) is False


def test_runtime_errors_are_retryable() -> None:
    assert ExecutionService._is_retryable(RuntimeError("temporary ffmpeg failure")) is True
