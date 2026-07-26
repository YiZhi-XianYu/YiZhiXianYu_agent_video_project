class ToolExecutionError(RuntimeError):
    """Tool failure with an explicit retryability decision."""

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable
