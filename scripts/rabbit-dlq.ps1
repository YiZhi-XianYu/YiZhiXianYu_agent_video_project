param(
    [ValidateSet('list','peek','replay')]
    [string]$Action = 'list',
    [string]$Queue = 'avp.task.dead.v1',
    [int]$Count = 10,
    [string]$RabbitContainer = 'avp-rabbitmq',
    [string]$ManagementUrl = 'http://127.0.0.1:15672',
    [string]$Username = $(if ($env:RABBITMQ_DEFAULT_USER) { $env:RABBITMQ_DEFAULT_USER } else { 'agentvideo' }),
    [string]$Password = $env:RABBITMQ_DEFAULT_PASS,
    [string]$TaskExchange = 'avp.task.v1',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw 'Set RABBITMQ_DEFAULT_PASS or pass -Password explicitly.'
}
$encodedQueue = [uri]::EscapeDataString($Queue)
$credential = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$Username`:$Password"))
$headers = @{ Authorization = "Basic $credential"; 'Content-Type' = 'application/json' }

if ($Action -eq 'list') {
    $queues = Invoke-RestMethod -Headers $headers -Uri "$ManagementUrl/api/queues/%2F" -Method Get
    $queues | Where-Object { $_.name -eq $Queue } | Select-Object name, messages, messages_ready, messages_unacknowledged, consumers | Format-Table -AutoSize
    exit 0
}

if ($Action -eq 'replay' -and -not $Force) {
    throw 'Replay changes broker state. Re-run with -Force after reviewing the DLQ with -Action peek.'
}

$ackMode = if ($Action -eq 'replay') { 'ack_requeue_false' } else { 'ack_requeue_true' }
$body = @{ count = [Math]::Min([Math]::Max($Count, 1), 100); ackmode = $ackMode; encoding = 'auto'; truncate = 200000 } | ConvertTo-Json
$messages = Invoke-RestMethod -Headers $headers -Uri "$ManagementUrl/api/queues/%2F/$encodedQueue/get" -Method Post -Body $body
if ($Action -eq 'peek') {
    $messages | Select-Object message_count, payload, properties, routing_key | Format-List
    exit 0
}

if ($messages.Count -eq 0) { Write-Output 'DLQ is empty.'; exit 0 }
$replayed = 0
foreach ($message in @($messages)) {
    $publish = @{
        properties = $message.properties
        routing_key = $(if ($message.routing_key -and $message.routing_key -notlike 'task.dead*') { $message.routing_key } else { 'task.light.requested' })
        payload = $message.payload
        payload_encoding = 'string'
    } | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Headers $headers -Uri "$ManagementUrl/api/exchanges/%2F/$([uri]::EscapeDataString($TaskExchange))/publish" -Method Post -Body $publish | Out-Null
    $replayed++
}
Write-Output "Replayed $replayed message(s) from $Queue to $TaskExchange. Verify task attempt/idempotency and inspect the audit log before replaying again."
