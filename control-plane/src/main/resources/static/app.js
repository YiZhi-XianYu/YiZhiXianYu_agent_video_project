const state = { projectId: null, assetId: null, workflowRunId: null, timer: null };

const el = (id) => document.getElementById(id);

async function request(url, options = {}) {
    const response = await fetch(url, options);
    const body = response.status === 204 ? null : await response.json();
    if (!response.ok) throw new Error(body?.message || body?.detail || `HTTP ${response.status}`);
    return body;
}

el("create-project").addEventListener("click", async () => {
    try {
        const project = await request("/api/v1/projects", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ name: el("project-name").value })
        });
        state.projectId = project.id;
        el("project-result").textContent = `已创建：${project.name} (${project.id})`;
        el("upload-video").disabled = false;
        el("asset-result").textContent = "请选择视频并上传";
    } catch (error) { showError(error); }
});

el("upload-video").addEventListener("click", async () => {
    const file = el("video-file").files[0];
    if (!file) return showError(new Error("请先选择视频文件"));
    const form = new FormData();
    form.append("file", file);
    setServiceState("正在上传视频...");
    try {
        const asset = await request(`/api/v1/projects/${state.projectId}/assets`, { method: "POST", body: form });
        state.assetId = asset.id;
        el("asset-result").textContent = `已上传：${asset.fileName}，${formatBytes(asset.sizeBytes)}`;
        el("start-workflow").disabled = false;
        el("workflow-result").textContent = "素材可用，可以启动分析";
        setServiceState("素材上传完成");
    } catch (error) { showError(error); }
});

el("start-workflow").addEventListener("click", async () => {
    clearInterval(state.timer);
    try {
        const accepted = await request(`/api/v1/projects/${state.projectId}/video-proxy-runs`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ assetId: state.assetId, quality: el("proxy-quality").value })
        });
        state.workflowRunId = accepted.workflowRunId;
        el("workflow-result").textContent = `Workflow 已创建：${accepted.workflowRunId}，输出 ${el("proxy-quality").value}`;
        setServiceState("Java 正在执行两节点代理视频工作流");
        await refreshRun();
        state.timer = setInterval(refreshRun, 1200);
    } catch (error) { showError(error); }
});

async function refreshRun() {
    if (!state.workflowRunId) return;
    try {
        const run = await request(`/api/v1/workflow-runs/${state.workflowRunId}`);
        renderRun(run);
        if (["SUCCEEDED", "FAILED"].includes(run.status)) {
            clearInterval(state.timer);
            setServiceState(run.status === "SUCCEEDED" ? "代理视频链路已打通" : "工作流执行失败");
        }
    } catch (error) { showError(error); }
}

function renderRun(run) {
    setStatus(el("run-status"), run.status);
    el("run-status").textContent = run.status;
    const probeTask = run.tasks.find((task) => task.nodeKey === "video_probe");
    const proxyTask = run.tasks.find((task) => task.nodeKey === "video_proxy_generate");
    renderTask("probe", probeTask);
    renderTask("proxy", proxyTask);
    const failedTask = run.tasks.find((task) => task.errorMessage);
    if (run.errorMessage || failedTask) showError(new Error(run.errorMessage || failedTask.errorMessage));

    const metadataArtifact = probeTask?.artifacts.find((artifact) => artifact.type === "VIDEO_METADATA");
    if (metadataArtifact) renderMetadata(metadataArtifact);
    const proxyArtifact = proxyTask?.artifacts.find((artifact) => artifact.type === "VIDEO_PROXY");
    if (proxyArtifact) renderProxy(proxyArtifact);
}

function renderTask(prefix, task) {
    if (!task) return;
    setStatus(el(`${prefix}-task-status`), task.status);
    el(`${prefix}-task-status`).textContent = task.status;
    el(`${prefix}-task-progress`).textContent = `${task.progress}%`;
    el(`${prefix}-task-attempt`).textContent = task.attempt;
    el(`${prefix}-progress-bar`).style.width = `${task.progress}%`;
}

function renderMetadata(artifact) {
    el("metadata-artifact-state").textContent = `已生成 ${artifact.type}`;
    const metadata = JSON.parse(artifact.metadataJson);
    el("meta-duration").textContent = `${(metadata.durationMs / 1000).toFixed(2)} 秒`;
    el("meta-resolution").textContent = `${metadata.width ?? "-"} × ${metadata.height ?? "-"}`;
    el("meta-fps").textContent = metadata.fps ?? "-";
    el("meta-video-codec").textContent = metadata.videoCodec ?? "-";
    el("meta-audio-codec").textContent = metadata.hasAudio ? (metadata.audioCodec ?? "未知") : "无音轨";
    el("meta-size").textContent = formatBytes(metadata.sizeBytes);
}

function renderProxy(artifact) {
    const metadata = JSON.parse(artifact.metadataJson);
    el("proxy-artifact-state").textContent = `已生成 ${artifact.type}，${formatBytes(metadata.sizeBytes)}`;
    el("proxy-preview").hidden = false;
    if (el("proxy-video").getAttribute("src") !== artifact.contentUrl) {
        el("proxy-video").src = artifact.contentUrl;
    }
    el("proxy-download").href = artifact.contentUrl;
    el("proxy-details").textContent = `${metadata.quality ?? "-"} · ${metadata.width ?? "-"} × ${metadata.height ?? "-"} · ${metadata.fps ?? 30} FPS · ${metadata.videoCodec ?? "h264"} · ${formatBytes(metadata.sizeBytes)}`;
}

function setStatus(node, status) {
    node.className = "status " + (status === "SUCCEEDED" ? "success" : status === "FAILED" ? "failed" : ["RUNNING", "DISPATCHING", "READY"].includes(status) ? "running" : "neutral");
}

function setServiceState(text) { el("service-state").textContent = text; }
function showError(error) { el("error-box").hidden = false; el("error-box").textContent = error.message; setServiceState("发生错误"); }
function formatBytes(value) {
    if (value == null) return "-";
    const units = ["B", "KB", "MB", "GB"];
    let index = 0, size = Number(value);
    while (size >= 1024 && index < units.length - 1) { size /= 1024; index++; }
    return `${size.toFixed(index ? 2 : 0)} ${units[index]}`;
}
