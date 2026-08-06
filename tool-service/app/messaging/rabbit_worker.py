from __future__ import annotations

import json
import logging
import threading

import pika
import httpx
from prometheus_client import Counter, Gauge

from app.core.config import settings
from app.core.models import ToolExecutionRequest
from app.execution.service import execution_service

logger = logging.getLogger(__name__)

RABBIT_CONSUMED_TOTAL = Counter("agentvideo_rabbit_messages_consumed_total", "Rabbit task deliveries", ["resource_group"])
RABBIT_ACK_TOTAL = Counter("agentvideo_rabbit_messages_ack_total", "Rabbit task acknowledgements", ["resource_group"])
RABBIT_REJECTED_TOTAL = Counter("agentvideo_rabbit_messages_rejected_total", "Rabbit task messages sent to DLQ", ["resource_group"])
RABBIT_CONNECTED = Gauge("agentvideo_rabbit_worker_connected", "Rabbit worker connection state", ["resource_group"])


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
                RABBIT_CONNECTED.labels(settings.worker_resource_group).set(1)
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
                RABBIT_CONNECTED.labels(settings.worker_resource_group).set(0)
                self._connection = None

    def _consume(self, channel, method, properties, body: bytes) -> None:
        try:
            group = settings.worker_resource_group
            RABBIT_CONSUMED_TOTAL.labels(group).inc()
            message = json.loads(body.decode("utf-8"))
            if message.get("schemaVersion") != "1.0":
                raise ValueError("Unsupported task message schema")
            if not message.get("messageId"):
                raise ValueError("Task messageId is required")
            if not message.get("taskRunId") or not message.get("workflowRunId"):
                raise ValueError("workflowRunId and taskRunId are required")
            request = ToolExecutionRequest.model_validate(message["request"])
            # Persist first, claim in Control Plane, then schedule.  Without
            # this hand-off a short task can finish and callback before the
            # MySQL ToolExecution row exists, losing the terminal result.
            record = execution_service.submit(request, schedule=False)
            workflow_id = message["workflowRunId"]
            task_id = message["taskRunId"]
            if workflow_id and task_id:
                with httpx.Client(timeout=10) as client:
                    response = client.post(
                        f"{settings.control_plane_base_url.rstrip('/')}/internal/tool-claims/{workflow_id}/{task_id}",
                        json={"idempotencyKey": request.idempotency_key, "executionId": record.execution_id,
                              "status": record.status, "workerToken": settings.rabbitmq_worker_token},
                        headers={"X-Internal-Worker-Token": settings.rabbitmq_worker_token} if settings.rabbitmq_worker_token else {},
                    )
                    response.raise_for_status()
                    claim = response.json() if response.content else {"accepted": True}
                    if claim.get("accepted", True):
                        execution_service.dispatch(record.execution_id)
                    else:
                        # Old attempt/token: acknowledge and discard without
                        # executing a stale task.
                        logger.warning("Discarding stale Rabbit task execution=%s", record.execution_id)
            else:
                execution_service.dispatch(record.execution_id)
            channel.basic_ack(delivery_tag=method.delivery_tag)
            RABBIT_ACK_TOTAL.labels(group).inc()
        except Exception:
            logger.exception("Rabbit task message rejected")
            # Poison messages go to the broker DLQ; they must not spin forever.
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            RABBIT_REJECTED_TOTAL.labels(settings.worker_resource_group).inc()


rabbit_worker = RabbitTaskWorker()
