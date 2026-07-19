const state = { projectId: null, assetId: null, assetIds: [], assets: [], workflowRunId: null, timer: null };

const el = (id) => document.getElementById(id);

document.addEventListener("DOMContentLoaded", loadProjects);

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
        await loadProjects(project.id);
        await loadWorkflowHistory();
    } catch (error) { showError(error); }
});

el("load-project").addEventListener("click", async () => {
    const projectId = el("project-select").value;
    if (!projectId) return showError(new Error("请选择一个历史项目"));
    try {
        clearInterval(state.timer);
        const project = el("project-select").selectedOptions[0];
        const assets = await request(`/api/v1/projects/${projectId}/assets`);
        state.projectId = projectId;
        state.workflowRunId = null;
        state.assets = assets;
        state.assetId = assets[0]?.id || null;
        renderAssets(assets);
        el("project-result").textContent = `已载入：${project.dataset.name} (${projectId})`;
        el("upload-video").disabled = false;
        el("asset-result").textContent = assets.length ? `已读取 ${assets.length} 个历史素材` : "项目暂无素材，可继续上传";
        el("workflow-result").textContent = assets.length ? "历史素材可用，可以启动新分析" : "需要先上传视频";
        await loadWorkflowHistory();
        setServiceState("历史项目已载入");
    } catch (error) { showError(error); }
});

el("load-workflow").addEventListener("click", async () => {
    const workflowRunId = el("workflow-select").value;
    if (!workflowRunId) return showError(new Error("请选择一个历史 Workflow"));
    try {
        clearInterval(state.timer);
        state.workflowRunId = workflowRunId;
        const run = await request(`/api/v1/workflow-runs/${workflowRunId}`);
        renderRun(run);
        el("workflow-result").textContent = `正在回看历史 Workflow：${workflowRunId}`;
        el("error-box").hidden = true;
        setServiceState(run.status === "RUNNING" ? "历史 Workflow 仍在运行" : "历史 Workflow 已载入");
        if (!["SUCCEEDED", "FAILED"].includes(run.status)) {
            state.timer = setInterval(refreshRun, 1200);
        }
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
        setServiceState("Java 正在执行多素材分析与决策工作流");
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
            setServiceState(run.status === "SUCCEEDED" ? "评分、排序、高光与 Timeline 已完成" : "工作流执行失败");
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
    renderDecisions(run.tasks || []);
}

async function loadProjects(selectedId = null) {
    try {
        const projects = await request("/api/v1/projects");
        const select = el("project-select");
        select.replaceChildren(new Option(projects.length ? "请选择历史项目" : "暂无历史项目", ""));
        projects.forEach(project => {
            const option = new Option(`${project.name} · ${project.status}`, project.id);
            option.dataset.name = project.name;
            select.add(option);
        });
        if (selectedId) select.value = selectedId;
        el("load-project").disabled = !projects.length;
    } catch (error) { showError(error); }
}

async function loadWorkflowHistory(selectedId = null) {
    const select = el("workflow-select");
    if (!state.projectId) {
        select.replaceChildren(new Option("请先载入项目", ""));
        select.disabled = true;
        el("load-workflow").disabled = true;
        return;
    }
    const runs = await request(`/api/v1/projects/${state.projectId}/workflow-runs`);
    select.replaceChildren(new Option(runs.length ? "请选择历史 Workflow" : "暂无历史 Workflow", ""));
    runs.forEach(run => {
        const timestamp = run.createdAt ? new Date(run.createdAt).toLocaleString("zh-CN", { hour12: false }) : "未知时间";
        const label = `${timestamp} · ${run.workflowType} · ${run.status} · ${run.assetCount}素材/${run.taskCount}任务`;
        select.add(new Option(label, run.id));
    });
    if (selectedId) select.value = selectedId;
    select.disabled = !runs.length;
    el("load-workflow").disabled = !runs.length;
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
            const retryLabel = task.retryCount ? ` · 重试 ${task.retryCount}` : "";
            card.innerHTML = `<div class="node-topline"><span class="node-icon">${task.nodeKey.slice(0, 2).toUpperCase()}</span></div><h3>${task.nodeKey}</h3><p>${asset?.fileName || "Workflow"}</p><p><code>${task.toolName}@${task.toolVersion}</code></p><div class="progress"><span style="width:${task.progress}%"></span></div><div class="node-meta"><span>${task.progress}%</span><span>尝试 ${task.attempt}${retryLabel}</span></div>`;
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
    const qualityByShot = new Map(tasks.flatMap(task => task.artifacts || [])
        .filter(artifact => artifact.type === "SHOT_QUALITY")
        .flatMap(artifact => parseMetadata(artifact).shots || [])
        .map(shot => [shot.shotId, shot]));
    const rankingByShot = new Map(tasks.flatMap(task => task.artifacts || [])
        .filter(artifact => artifact.type === "SHOT_RANKING")
        .flatMap(artifact => parseMetadata(artifact).shots || [])
        .map(shot => [shot.shotId, shot]));
    const selectedIds = new Set(tasks.flatMap(task => task.artifacts || [])
        .filter(artifact => artifact.type === "HIGHLIGHT_SET")
        .flatMap(artifact => parseMetadata(artifact).shots || [])
        .map(shot => shot.shotId));
    const shotCount = groups.reduce((total, group) => total + group.shots.length, 0);
    el("shots-panel").hidden = !shotCount;
    el("shot-count").textContent = `${shotCount} SHOTS`;
    el("shot-grid").replaceChildren(...groups.flatMap(group => {
        const heading = document.createElement("h3");
        heading.className = "shot-group-title";
        heading.textContent = group.asset?.fileName || "素材";
        return [heading, ...group.shots.map(shot => {
            const card = document.createElement("article");
            card.className = `shot-card${selectedIds.has(shot.shotId) ? " selected" : ""}`;
            const keyframe = keyframes.get(shot.keyframeArtifactId);
            if (keyframe) {
                const image = document.createElement("img");
                image.src = keyframe.contentUrl;
                image.alt = `${group.asset?.fileName || "素材"} Shot ${shot.index + 1} 关键帧`;
                image.loading = "lazy";
                card.append(image);
            }
            const title = document.createElement("strong");
            title.textContent = `Shot ${shot.index + 1}`;
            const range = document.createElement("span");
            range.textContent = `${(shot.startMs / 1000).toFixed(2)}s - ${(shot.endMs / 1000).toFixed(2)}s`;
            const detail = document.createElement("small");
            detail.textContent = `${(shot.durationMs / 1000).toFixed(2)} 秒 · 置信度 ${shot.boundaryConfidence}`;
            card.append(title, range, detail);
            const quality = qualityByShot.get(shot.shotId);
            const ranking = rankingByShot.get(shot.shotId);
            if (quality) {
                const total = document.createElement("strong");
                total.className = "score-total";
                total.textContent = `质量 ${(quality.qualityScore * 100).toFixed(1)}${ranking ? ` · #${ranking.rank}` : ""}`;
                const scores = document.createElement("div");
                scores.className = "score-grid";
                scores.innerHTML = `<span>清晰 ${(quality.clarity * 100).toFixed(0)}</span><span>曝光 ${(quality.exposure * 100).toFixed(0)}</span><span>稳定 ${(quality.stability * 100).toFixed(0)}</span><span>构图 ${(quality.composition * 100).toFixed(0)}</span>`;
                card.append(total, scores);
            }
            if (selectedIds.has(shot.shotId)) {
                const badge = document.createElement("small");
                badge.textContent = "已入选高光 Timeline";
                card.append(badge);
            }
            return card;
        })];
    }));
}

function renderDecisions(tasks) {
    const artifacts = tasks.flatMap(task => task.artifacts || []);
    const ranking = artifacts.find(artifact => artifact.type === "SHOT_RANKING");
    const story = artifacts.find(artifact => artifact.type === "STORY_PLAN");
    const highlights = artifacts.find(artifact => artifact.type === "HIGHLIGHT_SET");
    const timeline = artifacts.find(artifact => artifact.type === "TIMELINE");
    el("decision-panel").hidden = !timeline;
    if (!timeline) return;
    const rankingData = ranking ? parseMetadata(ranking) : {};
    const storyData = story ? parseMetadata(story) : {};
    const highlightData = highlights ? parseMetadata(highlights) : {};
    const timelineData = parseMetadata(timeline);
    el("timeline-duration").textContent = `${(timelineData.durationMs / 1000).toFixed(2)}s`;
    const summary = [
        ["跨素材候选", `${rankingData.shotCount ?? 0} shots`],
        ["入选高光", `${highlightData.selectedShotCount ?? 0} shots`],
        ["Timeline", `${timelineData.canvas?.width ?? "-"}×${timelineData.canvas?.height ?? "-"} @ ${timelineData.canvas?.fps ?? "-"} FPS`]
    ];
    el("decision-summary").replaceChildren(...summary.map(([label, value]) => {
        const item = document.createElement("div");
        item.innerHTML = `<span>${label}</span><strong>${value}</strong>`;
        return item;
    }));
    el("story-beats").replaceChildren(...(storyData.beats || []).map(beat => {
        const item = document.createElement("article");
        item.className = "story-beat";
        item.innerHTML = `<strong>${beat.role}</strong><span>${(beat.actualDurationMs / 1000).toFixed(2)}s / ${(beat.targetDurationMs / 1000).toFixed(2)}s</span><small>${beat.shots?.length ?? 0} shots</small>`;
        return item;
    }));
    const clips = timelineData.tracks?.find(track => track.type === "VIDEO")?.clips || [];
    el("timeline-track").replaceChildren(...clips.map((clip, index) => {
        const item = document.createElement("div");
        item.className = "timeline-clip";
        const asset = state.assets.find(value => value.id === clip.assetId);
        item.innerHTML = `<strong>${index + 1}. ${asset?.fileName || clip.assetId}</strong><small>${clip.storyRole} · ${(clip.sourceInMs / 1000).toFixed(2)}s - ${(clip.sourceOutMs / 1000).toFixed(2)}s</small><small>rank #${clip.selectionRank}</small>`;
        return item;
    }));
}

function parseMetadata(artifact) {
    try { return JSON.parse(artifact.metadataJson) || {}; } catch { return {}; }
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
    node.className = "status " + (status === "SUCCEEDED" ? "success" : status === "FAILED" ? "failed" : ["RUNNING", "DISPATCHING", "READY", "RETRY_WAIT"].includes(status) ? "running" : "neutral");
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
