const state = { projectId: null, assetId: null, assetIds: [], assets: [], workflowRunId: null, timer: null };

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
        el("asset-result").textContent = "请选择一个或多个视频并上传";
    } catch (error) { showError(error); }
});

el("upload-video").addEventListener("click", async () => {
    const files = Array.from(el("video-file").files);
    if (!files.length) return showError(new Error("请先选择至少一个视频文件"));
    const form = new FormData();
    files.forEach(file => form.append("files", file));
    setServiceState("正在上传视频...");
    try {
        const assets = await request(`/api/v1/projects/${state.projectId}/assets/batch`, { method: "POST", body: form });
        state.assets.push(...assets);
        state.assetIds = state.assets.map(asset => asset.id);
        state.assetId = state.assetIds[state.assetIds.length - 1];
        renderAssets(state.assets);
        el("asset-result").textContent = `已上传 ${assets.length} 个素材，可全部参与分析`;
        el("start-workflow").disabled = false;
        el("workflow-result").textContent = "素材可用，可以启动分析";
        setServiceState("素材上传完成");
    } catch (error) { showError(error); }
});

el("start-workflow").addEventListener("click", async () => {
    clearInterval(state.timer);
    try {
        const accepted = await request(`/api/v1/projects/${state.projectId}/multi-asset-analysis-runs`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ assetIds: state.assetIds, quality: el("proxy-quality").value })
        });
        state.workflowRunId = accepted.workflowRunId;
        el("workflow-result").textContent = `Workflow 已创建：${accepted.workflowRunId}，输出 ${el("proxy-quality").value}`;
        el("error-box").hidden = true;
        setServiceState("Java 正在执行多素材分析工作流");
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
            setServiceState(run.status === "SUCCEEDED" ? "多素材分析链路已完成" : "工作流执行失败");
        }
    } catch (error) { showError(error); }
}

function renderRun(run) {
    setStatus(el("run-status"), run.status);
    el("run-status").textContent = run.status;
    renderAssets(run.assets || state.assets);
    renderTasks(run.tasks || []);
    const failedTask = run.tasks.find((task) => task.errorMessage);
    if (run.errorMessage || failedTask) showError(new Error(run.errorMessage || failedTask.errorMessage));

    const metadataArtifact = (run.tasks || []).flatMap(task => task.artifacts || []).find(artifact => artifact.type === "VIDEO_METADATA");
    if (metadataArtifact) renderMetadata(metadataArtifact);
    const proxyArtifact = (run.tasks || []).flatMap(task => task.artifacts || []).find(artifact => artifact.type === "VIDEO_PROXY");
    if (proxyArtifact) renderProxy(proxyArtifact);
    renderShots(run.tasks || []);
}

function renderAssets(assets) {
    if (assets) state.assets = assets;
    state.assetIds = state.assets.map(asset => asset.id);
    el("asset-list").replaceChildren(...state.assets.map((asset, index) => {
        const item = document.createElement("div");
        item.className = "asset-item";
        item.textContent = `${index + 1}. ${asset.fileName} · ${formatBytes(asset.sizeBytes)}`;
        return item;
    }));
    el("start-workflow").disabled = !state.assetIds.length;
}

function renderTasks(tasks) {
    const grouped = new Map();
    tasks.forEach(task => {
        const key = task.assetId || "workflow";
        if (!grouped.has(key)) grouped.set(key, []);
        grouped.get(key).push(task);
    });
    el("task-grid").replaceChildren(...Array.from(grouped.entries()).map(([assetId, assetTasks]) => {
        const asset = state.assets.find(item => item.id === assetId);
        const group = document.createElement("section");
        group.className = "task-group";
        const heading = document.createElement("h3");
        heading.className = "task-group-title";
        heading.textContent = asset?.fileName || "Workflow";
        group.append(heading, ...assetTasks.map(task => {
            const card = document.createElement("article");
            card.className = "node task-card";
            const status = document.createElement("span");
            status.className = "status";
            setStatus(status, task.status);
            status.textContent = task.status;
            card.innerHTML = `<div class="node-topline"><span class="node-icon">${task.nodeKey.slice(0, 2).toUpperCase()}</span></div><h3>${task.nodeKey}</h3><p>${asset?.fileName || "Workflow"}</p><p><code>${task.toolName}@${task.toolVersion}</code></p><div class="progress"><span style="width:${task.progress}%"></span></div><div class="node-meta"><span>${task.progress}%</span><span>尝试 ${task.attempt}</span></div>`;
            card.querySelector(".node-topline").append(status);
            return card;
        }));
        return group;
    }));
}

function renderShots(tasks) {
    const keyframes = new Map(tasks.flatMap(task => task.artifacts || [])
        .filter(artifact => artifact.type === "KEYFRAME_IMAGE")
        .map(artifact => [artifact.externalArtifactId, artifact]));
    const groups = tasks.filter(task => task.artifacts?.some(artifact => artifact.type === "SHOT_LIST"))
        .map(task => {
            const asset = state.assets.find(item => item.id === task.assetId);
            const shots = task.artifacts.filter(artifact => artifact.type === "SHOT_LIST").flatMap(artifact => {
                try { return JSON.parse(artifact.metadataJson)?.shots || []; } catch { return []; }
            });
            return { asset, shots };
        });
    const shotCount = groups.reduce((total, group) => total + group.shots.length, 0);
    el("shots-panel").hidden = !shotCount;
    el("shot-count").textContent = `${shotCount} SHOTS`;
    el("shot-grid").replaceChildren(...groups.flatMap(group => {
        const heading = document.createElement("h3");
        heading.className = "shot-group-title";
        heading.textContent = group.asset?.fileName || "素材";
        return [heading, ...group.shots.map(shot => {
            const card = document.createElement("article");
            card.className = "shot-card";
            const keyframe = keyframes.get(shot.keyframeArtifactId);
            if (keyframe) {
                const image = document.createElement("img");
                image.src = keyframe.contentUrl;
                image.alt = `${group.asset?.fileName || "素材"} Shot ${shot.index + 1} 关键帧`;
                image.loading = "eager";
                card.append(image);
            }
            const title = document.createElement("strong");
            title.textContent = `Shot ${shot.index + 1}`;
            const range = document.createElement("span");
            range.textContent = `${(shot.startMs / 1000).toFixed(2)}s - ${(shot.endMs / 1000).toFixed(2)}s`;
            const detail = document.createElement("small");
            detail.textContent = `${(shot.durationMs / 1000).toFixed(2)} 秒 · 置信度 ${shot.boundaryConfidence}`;
            card.append(title, range, detail);
            return card;
        })];
    }));
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
