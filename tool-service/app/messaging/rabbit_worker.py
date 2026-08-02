from __future__ import annotations

import json
import logging
import threading

import pika
import httpx

from app.core.config import settings
from app.core.models import ToolExecutionRequest
from app.execution.service import execution_service

logger = logging.getLogger(__name__)


class RabbitTaskWorker:
    def __init__(self) -> None:
        self.enabled = settings.rabbitmq_enabled
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._connection = None

    def start(self) -> None:
        if not self.enabled:
            return
        self._thread = threading.Thread(target=self._run, name="rabbit-task-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        connection = self._connection
        if connection is not None and connection.is_open:
            connection.add_callback_threadsafe(connection.close)
        if self._thread is not None:
            self._thread.join(timeout=5)

    def _run(self) -> None:
        queue = f"avp.task.{settings.worker_resource_group.lower()}.v1"
        while not self._stop.is_set():
            try:
                credentials = pika.PlainCredentials(settings.rabbitmq_username, settings.rabbitmq_password)
                parameters = pika.ConnectionParameters(
                    host=settings.rabbitmq_host, port=settings.rabbitmq_port,
                    credentials=credentials, heartbeat=30, blocked_connection_timeout=10,
                )
                connection = pika.BlockingConnection(parameters)
                self._connection = connection
                channel = connection.channel()
                channel.basic_qos(prefetch_count=max(1, settings.rabbitmq_prefetch))
                channel.queue_declare(
                    queue=queue,
                    durable=True,
                    arguments={"x-dead-letter-exchange": "avp.dead.v1"},
                )
                channel.basic_consume(queue=queue, on_message_callback=self._consume, auto_ack=False)
                logger.info("Rabbit worker started queue=%s", queue)
                while connection.is_open and not self._stop.is_set():
                    connection.process_data_events(time_limit=1)
            except Exception:
                logger.exception("Rabbit worker connection failed; retrying")
                self._stop.wait(5)
            finally:
                self._connection = None

    def _consume(self, channel, method, properties, body: bytes) -> None:
        try:
            message = json.loads(body.decode("utf-8"))
            if message.get("schemaVersion") != "1.0":
                raise ValueError("Unsupported task message schema")
            request = ToolExecutionRequest.model_validate(message["request"])
            record = execution_service.submit(request)
            workflow_id = message.get("workflowRunId")
            task_id = message.get("taskRunId")
            if workflow_id and task_id:
                with httpx.Client(timeout=10) as client:
                    response = client.post(
                        f"{settings.control_plane_base_url.rstrip('/')}/internal/tool-claims/{workflow_id}/{task_id}",
                        json={"idempotencyKey": request.idempotency_key, "executionId": record.execution_id,
                              "status": record.status, "workerToken": settings.rabbitmq_worker_token},
                        headers={"X-Internal-Worker-Token": settings.rabbitmq_worker_token} if settings.rabbitmq_worker_token else {},
                    )
                    response.raise_for_status()
            channel.basic_ack(delivery_tag=method.delivery_tag)
        except Exception:
            logger.exception("Rabbit task message rejected")
            # Poison messages go to the broker DLQ; they must not spin forever.
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)


rabbit_worker = RabbitTaskWorker()
