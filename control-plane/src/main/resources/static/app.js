const state = { projectId: null, assetId: null, assetIds: [], assets: [], workflowRunId: null, timer: null,
    editMode: false, customPlan: null, lockedShotIds: new Set(), customPlanId: null,
    customPlanLoaded: false, renderWorkflowRunId: null, renderTimer: null,
    versions: [], versionsLoaded: false, dirty: false,
    compareMode: false, compareVersionId: null, compareVersionPlan: null, cachedTasks: [] };

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
        const durationPrompt = el("duration-prompt").value.trim();
        const body = { assetIds: state.assetIds, quality: el("proxy-quality").value };
        if (durationPrompt) body.durationPrompt = durationPrompt;
        const accepted = await request(`/api/v1/projects/${state.projectId}/multi-asset-analysis-runs`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body)
        });
        state.workflowRunId = accepted.workflowRunId;
        el("workflow-result").textContent = `Workflow 已创建：${accepted.workflowRunId}，输出 ${el("proxy-quality").value}`;
        el("error-box").hidden = true;
        setServiceState("Java 正在执行多素材分析与决策工作流");
        await refreshRun();
        state.timer = setInterval(refreshRun, 1200);
    } catch (error) { showError(error); }
});

el("toggle-edit-mode").addEventListener("click", () => toggleEditMode());
el("save-custom-plan").addEventListener("click", () => saveCustomPlan());
el("apply-custom-plan").addEventListener("click", () => applyCustomPlan());
el("toggle-version-panel").addEventListener("click", () => toggleVersionPanel());
el("close-diff").addEventListener("click", () => closeDiff());

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
    const proxyArtifacts = (run.tasks || []).flatMap(task =>
        (task.artifacts || []).filter(a => a.type === "VIDEO_PROXY").map(a => ({...a, taskAssetId: task.assetId}))
    );
    if (proxyArtifacts.length) renderProxies(proxyArtifacts);
    renderShots(run.tasks || []);
    renderDecisions(run.tasks || []);
    const renderedArtifact = (run.tasks || []).flatMap(t => t.artifacts || []).find(a => a.type === "RENDERED_VIDEO");
    if (renderedArtifact) renderRenderedVideo(renderedArtifact);
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
    const sceneByShot = new Map(tasks.flatMap(task => task.artifacts || [])
        .filter(artifact => artifact.type === "SCENE_TAGS")
        .flatMap(artifact => parseMetadata(artifact).shots || [])
        .map(shot => [shot.shotId, shot.sceneTags || []]));
    const objectByShot = new Map(tasks.flatMap(task => task.artifacts || [])
        .filter(artifact => artifact.type === "OBJECT_TAGS")
        .flatMap(artifact => parseMetadata(artifact).shots || [])
        .map(shot => [shot.shotId, shot.objectTags || []]));
    const personByShot = new Map(tasks.flatMap(task => task.artifacts || [])
        .filter(artifact => artifact.type === "PERSON_TAGS")
        .flatMap(artifact => parseMetadata(artifact).shots || [])
        .map(shot => [shot.shotId, shot.personTags || []]));
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
            const sceneTags = sceneByShot.get(shot.shotId) || [];
            const objectTags = objectByShot.get(shot.shotId) || [];
            const personTags = personByShot.get(shot.shotId) || [];
            const allTags = [...sceneTags, ...objectTags, ...personTags];
            if (allTags.length) {
                const tagRow = document.createElement("div");
                tagRow.className = "semantic-tags";
                allTags.forEach(tag => {
                    const chip = document.createElement("span");
                    chip.className = `semantic-chip tag-${tag.label ? tag.label.split("_")[0].toLowerCase() : "none"}`;
                    chip.textContent = tag.labelZh || tag.label;
                    chip.title = `${tag.label} · ${(tag.confidence * 100).toFixed(0)}%`;
                    tagRow.appendChild(chip);
                });
                card.append(tagRow);
            }
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

    state.cachedTasks = tasks;

    // Load custom plan once
    if (story && !state.customPlanLoaded) {
        state.customPlanLoaded = true;
        loadCustomPlan(storyData);
    }

    const beats = (state.editMode && state.customPlan) ? state.customPlan.beats : (storyData.beats || []);
    const effectiveTimeline = (state.editMode && state.customPlan) ? buildClientTimeline(beats) : timelineData;

    el("timeline-duration").textContent = `${(effectiveTimeline.durationMs / 1000).toFixed(2)}s`;

    // Show/hide edit toolbar
    el("edit-toolbar").hidden = !timeline;
    el("toggle-edit-mode").textContent = state.editMode ? "退出编辑" : "Edit Shots";
    el("save-custom-plan").hidden = !state.editMode;
    el("save-version-name").hidden = !state.editMode;
    el("apply-custom-plan").hidden = !state.editMode;
    el("toggle-version-panel").hidden = !state.editMode;
    el("toggle-version-panel").textContent = (!el("version-panel").hidden) ? "Hide Versions" : "Versions";
    if (!state.editMode) {
        el("version-panel").hidden = true;
    }

    const summary = [
        ["跨素材候选", `${rankingData.shotCount ?? 0} shots`],
        ["入选高光", `${highlightData.selectedShotCount ?? 0} shots`],
        ["Timeline", `${effectiveTimeline.canvas?.width ?? "-"}×${effectiveTimeline.canvas?.height ?? "-"} @ ${effectiveTimeline.canvas?.fps ?? "-"} FPS`]
    ];
    el("decision-summary").replaceChildren(...summary.map(([label, value]) => {
        const item = document.createElement("div");
        item.innerHTML = `<span>${label}</span><strong>${value}</strong>`;
        return item;
    }));
    const llmAudit = storyData.llmAudit;
    if (llmAudit) {
        const isLlm = llmAudit.finalSource === "LLM";
        const color = isLlm ? "#e67e22" : "#888";
        const text = isLlm ? "AI 生成" : "确定性算法";
        const sourceBadge = document.createElement("div");
        sourceBadge.innerHTML = `<span>故事来源</span><strong style="color:${color}">${text}</strong>`;
        el("decision-summary").appendChild(sourceBadge);
        if (llmAudit.provider && llmAudit.provider !== "none") {
            const auditDetail = document.createElement("div");
            const wasLLM = llmAudit.finalSource === "LLM";
            const wasFallback = llmAudit.finalSource === "DETERMINISTIC_FALLBACK";
            let statusText = "";
            if (wasLLM) { statusText = "通过校验，已采用"; }
            else if (wasFallback && llmAudit.validationErrors.length > 0) { statusText = "校验失败: " + llmAudit.validationErrors.slice(0, 2).join("; "); }
            else if (wasFallback) { statusText = "LLM 调用异常，已回退"; }
            auditDetail.innerHTML = `<span>LLM审计</span><small>${llmAudit.provider}/${llmAudit.model} · ${llmAudit.durationMs}ms · candidates=${llmAudit.inputCandidateCount}</small><small style="color:${wasLLM ? '#27ae60' : '#e74c3c'}">${statusText}</small>`;
            el("decision-summary").appendChild(auditDetail);
        }
    }

    // Build available shots map for replace dropdown
    const availableShots = state.editMode ? getAvailableShots(rankingData, beats) : [];

    el("story-beats").replaceChildren(...beats.map((beat, beatIndex) => {
        const item = document.createElement("article");
        item.className = "story-beat";
        const sourceLabel = (llmAudit && llmAudit.finalSource === "LLM") ? "✨ " : "⚙ ";
        const beatShots = beat.shots || [];
        const actualDur = beatShots.reduce((sum, s) => sum + (s.selectedDurationMs || (s.sourceOutMs - s.sourceInMs) || 0), 0);
        const targetDur = beat.targetDurationMs || 0;
        item.innerHTML = `<strong>${sourceLabel}${beat.role}</strong><span>${(actualDur / 1000).toFixed(2)}s / ${(targetDur / 1000).toFixed(2)}s</span><small>${beatShots.length} shots</small>`;
        if (beatShots.length || state.editMode) {
            const shotList = document.createElement("div");
            shotList.className = "beat-shot-list";

            if (state.editMode && availableShots.length > 0) {
                shotList.appendChild(createAddShotRow(availableShots, beatShots, beat, 0, tasks));
            }

            beatShots.forEach((shot, shotIndex) => {
                const shotRow = document.createElement("div");
                const isLocked = state.lockedShotIds.has(shot.shotId);
                shotRow.className = `beat-shot-row${state.editMode ? " editing" : ""}${isLocked ? " shot-locked" : ""}`;
                const asset = state.assets.find(a => a.id === shot.sourceAssetId);
                const srcLabel = asset?.fileName || shot.sourceAssetId?.slice(0, 8) || "?";
                const selDur = shot.selectedDurationMs || (shot.sourceOutMs - shot.sourceInMs) || 0;
                const timeRange = `${(shot.sourceInMs / 1000).toFixed(1)}s–${(shot.sourceOutMs / 1000).toFixed(1)}s`;
                shotRow.innerHTML = `<code>#${shot.rank || shotIndex + 1}</code><span class="beat-shot-asset">${srcLabel}</span><span class="beat-shot-time">${timeRange}</span>`;

                if (state.editMode) {
                    const actions = document.createElement("span");
                    actions.className = "shot-actions";

                    const lockBtn = document.createElement("button");
                    lockBtn.textContent = isLocked ? "解锁" : "锁定";
                    lockBtn.title = isLocked ? "Unlock shot" : "Lock shot";
                    lockBtn.addEventListener("click", () => {
                        if (isLocked) state.lockedShotIds.delete(shot.shotId);
                        else state.lockedShotIds.add(shot.shotId);
                        renderDecisions(tasks);
                    });
                    actions.appendChild(lockBtn);

                    if (!isLocked) {
                        if (shotIndex > 0) {
                            const upBtn = document.createElement("button");
                            upBtn.textContent = "↑";
                            upBtn.title = "Move up";
                            upBtn.addEventListener("click", () => {
                                state.dirty = true;
                                [beatShots[shotIndex - 1], beatShots[shotIndex]] = [beatShots[shotIndex], beatShots[shotIndex - 1]];
                                renderDecisions(tasks);
                            });
                            actions.appendChild(upBtn);
                        }
                        if (shotIndex < beatShots.length - 1) {
                            const downBtn = document.createElement("button");
                            downBtn.textContent = "↓";
                            downBtn.title = "Move down";
                            downBtn.addEventListener("click", () => {
                                state.dirty = true;
                                [beatShots[shotIndex], beatShots[shotIndex + 1]] = [beatShots[shotIndex + 1], beatShots[shotIndex]];
                                renderDecisions(tasks);
                            });
                            actions.appendChild(downBtn);
                        }

                        const replaceSelect = document.createElement("select");
                        replaceSelect.className = "beat-shot-replace-select";
                        replaceSelect.title = "Replace shot";
                        const defaultOpt = document.createElement("option");
                        defaultOpt.value = "";
                        defaultOpt.textContent = "替换...";
                        replaceSelect.appendChild(defaultOpt);
                        availableShots.forEach(av => {
                            const opt = document.createElement("option");
                            opt.value = av.shotId;
                            const avAsset = state.assets.find(a => a.id === av.sourceAssetId);
                            const avLabel = avAsset?.fileName || av.sourceAssetId?.slice(0, 8) || "?";
                            opt.textContent = `#${av.rank} ${avLabel} ${(av.durationMs / 1000).toFixed(1)}s`;
                            replaceSelect.appendChild(opt);
                        });
                        replaceSelect.addEventListener("change", () => {
                            if (!replaceSelect.value) return;
                            const newShot = availableShots.find(s => s.shotId === replaceSelect.value);
                            if (!newShot) return;
                            state.dirty = true;
                            beatShots[shotIndex] = {
                                shotId: newShot.shotId,
                                sourceAssetId: newShot.sourceAssetId,
                                sourceProxyArtifactId: newShot.sourceProxyArtifactId || "",
                                startMs: newShot.startMs || 0,
                                endMs: newShot.endMs || newShot.durationMs || 0,
                                sourceInMs: newShot.startMs || 0,
                                sourceOutMs: newShot.endMs || newShot.durationMs || 0,
                                selectedDurationMs: newShot.durationMs || 0,
                                rank: newShot.rank || 0,
                                storyRole: beat.role,
                                qualityScore: newShot.qualityScore || 0,
                                selectionReasons: ["MANUAL_REPLACEMENT"],
                            };
                            replaceSelect.value = "";
                            renderDecisions(tasks);
                        });
                        actions.appendChild(replaceSelect);

                        const removeBtn = document.createElement("button");
                        removeBtn.className = "shot-remove-btn";
                        removeBtn.textContent = "✕";
                        removeBtn.title = "Remove shot";
                        removeBtn.addEventListener("click", () => {
                            state.dirty = true;
                            beatShots.splice(shotIndex, 1);
                            renderDecisions(tasks);
                        });
                        actions.appendChild(removeBtn);
                    }

                    shotRow.appendChild(actions);
                }
                shotList.appendChild(shotRow);
            });

            if (state.editMode && availableShots.length > 0) {
                shotList.appendChild(createAddShotRow(availableShots, beatShots, beat, beatShots.length, tasks));
            }

            item.appendChild(shotList);
        }
        return item;
    }));

    // Render timeline: from custom plan in edit mode, from artifact otherwise
    if (effectiveTimeline.tracks?.[0]?.clips?.length) {
        const totalMs = effectiveTimeline.durationMs || 30000;
        el("timeline-track").replaceChildren(...effectiveTimeline.tracks[0].clips.map(clip => {
            const block = document.createElement("div");
            block.className = `timeline-clip role-${clip.storyRole}`;
            block.style.width = `${Math.max((clip.timelineOutMs - clip.timelineInMs) / totalMs * 100, 2)}%`;
            const asset = state.assets.find(a => a.id === clip.assetId);
            const srcLabel = asset?.fileName || clip.assetId?.slice(0, 8) || "?";
            const tIn = (clip.timelineInMs / 1000).toFixed(1);
            const tOut = (clip.timelineOutMs / 1000).toFixed(1);
            const srcIn = (clip.sourceInMs / 1000).toFixed(1);
            const srcOut = (clip.sourceOutMs / 1000).toFixed(1);
            block.innerHTML = `<span>#${clip.selectionRank} ${srcLabel}</span><small>${srcIn}s → ${srcOut}s</small><small>T+${tIn}s–${tOut}s</small>`;
            block.title = `${clip.storyRole} · #${clip.selectionRank} · ${srcLabel}\n源 ${srcIn}s–${srcOut}s  时间线 ${tIn}s–${tOut}s`;
            return block;
        }));
    } else {
        el("timeline-track").replaceChildren();
    }
}
 
function showError(error) {
    const box = el("error-box");
    box.hidden = false;
    box.textContent = error?.message || error?.toString() || "未知错误";
    console.error(error);
}

function setServiceState(text) {
    el("service-state").textContent = text;
}

function setStatus(element, status) {
    element.className = "status";
    switch (status) {
        case "RUNNING": element.classList.add("running"); break;
        case "SUCCEEDED": element.classList.add("success"); break;
        case "FAILED": element.classList.add("failed"); break;
        default: element.classList.add("neutral");
    }
}

function parseMetadata(artifact) {
    if (!artifact?.metadataJson) return {};
    try { return JSON.parse(artifact.metadataJson); } catch { return {}; }
}

function formatBytes(bytes) {
    if (bytes == null) return "?";
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function renderMetadata(artifact) {
    el("metadata-artifact-state").textContent = "已输出";
    const meta = parseMetadata(artifact);
    el("meta-duration").textContent = meta.durationMs ? `${(meta.durationMs / 1000).toFixed(2)}s` : "-";
    el("meta-resolution").textContent = meta.width && meta.height ? `${meta.width}×${meta.height}` : "-";
    el("meta-fps").textContent = meta.fps || "-";
    el("meta-video-codec").textContent = meta.videoCodec || "-";
    el("meta-audio-codec").textContent = meta.audioCodec || "-";
    el("meta-size").textContent = meta.sizeBytes ? formatBytes(meta.sizeBytes) : "-";
}


function renderProxies(proxies) {
    el("proxy-artifact-state").textContent = "已生成";
    const container = el("proxy-previews");
    container.replaceChildren(...proxies.map(proxy => {
        const section = document.createElement("section");
        section.className = "proxy-preview";
        const asset = state.assets.find(a => a.id === proxy.taskAssetId);
        const label = asset?.fileName || proxy.taskAssetId?.slice(0, 8) || "素材";
        const dl = document.createElement("a");
        dl.className = "download-link";
        dl.href = proxy.contentUrl;
        dl.download = "";
        dl.textContent = "下载 MP4";
        const vid = document.createElement("video");
        vid.controls = true;
        vid.preload = "metadata";
        vid.src = proxy.contentUrl;
        const info = document.createElement("p");
        info.className = "result";
        info.textContent = proxy.fileName || "代理视频";
        const header = document.createElement("div");
        header.className = "workspace-header";
        header.innerHTML = '<div><p class="eyebrow">GENERATED VIDEO ARTIFACT</p><h2>代理视频 &middot; ' + label + '</h2></div>';
        header.appendChild(dl);
        section.appendChild(header);
        section.appendChild(vid);
        section.appendChild(info);
        return section;
    }));
}

function renderRenderedVideo(artifact) {
    const section = el("rendered-video-section");
    section.hidden = false;
    el("rendered-video").src = artifact.contentUrl;
    el("render-download").href = artifact.contentUrl;
}

// ---------- P2: Manual Shot Editing ----------

async function loadCustomPlan(storyData) {
    try {
        const result = await request(`/api/v1/workflow-runs/${state.workflowRunId}/custom-story-plan`);
        if (result.custom && result.plan) {
            state.customPlan = result.plan;
            state.customPlanId = result.id;
            return;
        }
    } catch (e) { /* no saved plan */ }
    if (storyData && storyData.beats) {
        state.customPlan = JSON.parse(JSON.stringify(storyData));
    }
}

async function toggleEditMode() {
    if (state.editMode) {
        state.editMode = false;
        el("edit-status").textContent = "";
        el("version-panel").hidden = true;
        el("diff-panel").hidden = true;
        state.compareMode = false;
        state.compareVersionId = null;
        state.compareVersionPlan = null;
        const run = await request(`/api/v1/workflow-runs/${state.workflowRunId}`);
        renderRun(run);
        return;
    }
    if (!state.customPlan) {
        el("edit-status").textContent = "Loading...";
        try {
            const result = await request(`/api/v1/workflow-runs/${state.workflowRunId}/custom-story-plan`);
            if (result.custom && result.plan) {
                state.customPlan = result.plan;
                state.customPlanId = result.id;
            }
        } catch (e) { /* will clone below if still null */ }
        if (!state.customPlan) {
            const run = await request(`/api/v1/workflow-runs/${state.workflowRunId}`);
            const tasks = run.tasks || [];
            const artifacts = tasks.flatMap(t => t.artifacts || []);
            const story = artifacts.find(a => a.type === "STORY_PLAN");
            if (story) {
                const storyData = parseMetadata(story);
                if (storyData && storyData.beats) {
                    state.customPlan = JSON.parse(JSON.stringify(storyData));
                }
            }
            if (!state.customPlan) {
                el("edit-status").textContent = "No story plan found";
                return;
            }
        }
        el("edit-status").textContent = "";
    }
    state.editMode = true;
    const run = await request(`/api/v1/workflow-runs/${state.workflowRunId}`);
    renderRun(run);
    loadVersions();
}

async function saveCustomPlan() {
    if (!state.customPlan) return;
    try {
        const versionName = el("save-version-name").value.trim() || null;
        const result = await request(`/api/v1/workflow-runs/${state.workflowRunId}/custom-story-plan`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ plan: state.customPlan, versionName: versionName })
        });
        state.customPlanId = result.id;
        state.dirty = false;
        el("save-version-name").value = "";
        el("edit-status").textContent = "Plan saved";
        setTimeout(() => { if (el("edit-status").textContent === "Plan saved") el("edit-status").textContent = ""; }, 2000);
        await loadVersions();
    } catch (error) {
        el("edit-status").textContent = "Save failed: " + (error.message || "unknown");
    }
}

async function applyCustomPlan() {
    if (!state.customPlan) return;
    if (!state.customPlanId) {
        await saveCustomPlan();
        if (!state.customPlanId) return;
    }
    try {
        el("edit-status").textContent = "Applying & rendering...";
        const result = await request(`/api/v1/workflow-runs/${state.workflowRunId}/custom-story-plan/apply`, {
            method: "POST"
        });
        state.renderWorkflowRunId = result.workflowRunId;
        clearInterval(state.renderTimer);
        state.renderTimer = setInterval(pollRenderWorkflow, 2000);
        el("edit-status").textContent = "Render started: " + result.workflowRunId;
    } catch (error) {
        el("edit-status").textContent = "Apply failed: " + (error.message || "unknown");
    }
}

async function pollRenderWorkflow() {
    if (!state.renderWorkflowRunId) return;
    try {
        const run = await request(`/api/v1/workflow-runs/${state.renderWorkflowRunId}`);
        if (["SUCCEEDED", "FAILED"].includes(run.status)) {
            clearInterval(state.renderTimer);
            if (run.status === "SUCCEEDED") {
                el("edit-status").textContent = "Render complete";
                const artifacts = (run.tasks || []).flatMap(t => t.artifacts || []);
                const rendered = artifacts.find(a => a.type === "RENDERED_VIDEO");
                if (rendered) renderRenderedVideo(rendered);
            } else {
                el("edit-status").textContent = "Render failed: " + (run.errorMessage || "unknown");
            }
        } else {
            el("edit-status").textContent = "Rendering... " + run.progress + "%";
        }
    } catch (error) {
        el("edit-status").textContent = "Render poll error";
    }
}

function getAvailableShots(rankingData, beats) {
    if (!rankingData.shots) return [];
    const usedIds = new Set();
    (beats || []).forEach(beat => (beat.shots || []).forEach(shot => {
        if (!state.lockedShotIds.has(shot.shotId)) usedIds.add(shot.shotId);
    }));
    return rankingData.shots.filter(s => !usedIds.has(s.shotId));
}

function buildClientTimeline(beats) {
    const clips = [];
    let timelineIn = 0;
    (beats || []).forEach(beat => {
        (beat.shots || []).forEach(shot => {
            const duration = shot.selectedDurationMs || (shot.sourceOutMs - shot.sourceInMs) || 0;
            clips.push({
                clipId: "clip_" + (shot.shotId || ""),
                shotId: shot.shotId,
                assetId: shot.sourceAssetId,
                sourceProxyArtifactId: shot.sourceProxyArtifactId,
                sourceInMs: shot.sourceInMs,
                sourceOutMs: shot.sourceOutMs,
                sourceShotStartMs: shot.startMs || 0,
                sourceShotEndMs: shot.endMs || 0,
                timelineInMs: timelineIn,
                timelineOutMs: timelineIn + duration,
                playbackRate: 1.0,
                transitionIn: { type: "CUT", durationMs: 0 },
                selectionRank: shot.rank || 0,
                storyRole: beat.role || shot.storyRole,
                selectionReasons: shot.selectionReasons || [],
            });
            timelineIn += duration;
        });
    });
    return {
        durationMs: timelineIn,
        canvas: { width: 1920, height: 1080, fps: 30 },
        tracks: [{ type: "VIDEO", clips: clips }],
    };
}

function createAddShotRow(availableShots, beatShots, beat, insertIndex, tasks) {
    const row = document.createElement("div");
    row.className = "beat-shot-row beat-shot-add";
    row.style.justifyContent = "center";

    const addBtn = document.createElement("button");
    addBtn.textContent = "+";
    addBtn.title = "Add shot";
    addBtn.style.cssText = "width:24px;height:24px;padding:0;border-radius:50%;font-size:16px;line-height:1;font-weight:700;background:var(--accent);color:#052019;cursor:pointer;flex-shrink:0;";

    addBtn.addEventListener("click", () => {
        addBtn.replaceWith(buildAddSelect());
    });

    function buildAddSelect() {
        const select = document.createElement("select");
        select.className = "beat-shot-replace-select";
        select.style.maxWidth = "200px";
        const defaultOpt = document.createElement("option");
        defaultOpt.value = "";
        defaultOpt.textContent = "+";
        select.appendChild(defaultOpt);
        availableShots.forEach(av => {
            const opt = document.createElement("option");
            opt.value = av.shotId;
            const avAsset = state.assets.find(a => a.id === av.sourceAssetId);
            const avLabel = avAsset?.fileName || av.sourceAssetId?.slice(0, 8) || "?";
            opt.textContent = `#${av.rank} ${avLabel} ${(av.durationMs / 1000).toFixed(1)}s`;
            select.appendChild(opt);
        });
        select.addEventListener("change", () => {
            if (!select.value) return;
            const newShot = availableShots.find(s => s.shotId === select.value);
            if (!newShot) return;
            state.dirty = true;
            beatShots.splice(insertIndex, 0, {
                shotId: newShot.shotId,
                sourceAssetId: newShot.sourceAssetId,
                sourceProxyArtifactId: newShot.sourceProxyArtifactId || "",
                startMs: newShot.startMs || 0,
                endMs: newShot.endMs || newShot.durationMs || 0,
                sourceInMs: newShot.startMs || 0,
                sourceOutMs: newShot.endMs || newShot.durationMs || 0,
                selectedDurationMs: newShot.durationMs || 0,
                rank: newShot.rank || 0,
                storyRole: beat.role,
                qualityScore: newShot.qualityScore || 0,
                selectionReasons: ["MANUAL_ADDITION"],
            });
            select.value = "";
            refreshDecisions();
        });
        select.addEventListener("blur", () => {
            if (!select.value) select.replaceWith(buildAddSelect());
        });
        setTimeout(() => select.focus(), 0);
        return select;
    }

    row.appendChild(addBtn);
    return row;
}

// ---------- P3: Version Management ----------

function refreshDecisions() {
    if (state.cachedTasks.length) renderDecisions(state.cachedTasks);
}

async function loadVersions() {
    try {
        state.versions = await request(`/api/v1/workflow-runs/${state.workflowRunId}/custom-story-plan/version-list`);
        state.versionsLoaded = true;
        renderVersionList();
    } catch (e) {
        state.versions = [];
        state.versionsLoaded = false;
        el("version-count").textContent = "Load failed: " + (e.message || "unknown");
    }
}

function toggleVersionPanel() {
    if (!el("version-panel").hidden) {
        el("version-panel").hidden = true;
        el("diff-panel").hidden = true;
        state.compareMode = false;
        state.compareVersionId = null;
        state.compareVersionPlan = null;
        el("toggle-version-panel").textContent = "Versions";
    } else {
        el("version-panel").hidden = false;
        el("toggle-version-panel").textContent = "Hide Versions";
        loadVersions();
    }
}

function renderVersionList() {
    el("version-count").textContent = state.versions.length + " versions";
    el("version-list").replaceChildren(...state.versions.map(v => {
        const row = document.createElement("div");
        row.className = "version-row" + (v.id === state.customPlanId ? " current-draft" : "");
        const name = document.createElement("span");
        name.className = "version-name";
        name.textContent = v.versionName || "Unnamed";
        const meta = document.createElement("span");
        meta.className = "version-meta";
        const statusClass = v.status.toLowerCase();
        meta.innerHTML = `<span class="version-status status-${statusClass}">${v.status}</span>
            ${new Date(v.createdAt).toLocaleString("zh-CN", { hour12: false })}
            · ${v.beatCount} beats · ${v.shotCount} shots · ${(v.totalDurationMs / 1000).toFixed(1)}s`;
        const actions = document.createElement("span");
        actions.className = "version-actions";
        if (v.id !== state.customPlanId) {
            const loadBtn = document.createElement("button");
            loadBtn.textContent = "Load";
            loadBtn.addEventListener("click", () => loadVersion(v.id));
            actions.appendChild(loadBtn);
        }
        const compareBtn = document.createElement("button");
        compareBtn.textContent = (state.compareMode && state.compareVersionId === v.id) ? "Close" : "Compare";
        compareBtn.addEventListener("click", () => compareVersion(v.id));
        actions.appendChild(compareBtn);
        if (v.status !== "DRAFT") {
            const deleteBtn = document.createElement("button");
            deleteBtn.className = "version-delete-btn";
            deleteBtn.textContent = "Del";
            deleteBtn.addEventListener("click", () => deleteVersion(v.id));
            actions.appendChild(deleteBtn);
        }
        row.append(name, meta, actions);
        return row;
    }));
}

async function loadVersion(planId) {
    if (state.dirty) {
        if (!confirm("You have unsaved edits. Load version anyway?")) return;
    }
    try {
        const result = await request(`/api/v1/workflow-runs/${state.workflowRunId}/custom-story-plan/versions/${planId}`);
        state.customPlan = JSON.parse(JSON.stringify(result.plan));
        state.customPlanId = null;
        state.dirty = true;
        state.lockedShotIds = new Set();
        refreshDecisions();
        el("edit-status").textContent = "Loaded: " + (result.versionName || "Unnamed");
    } catch (error) {
        el("edit-status").textContent = "Load failed: " + (error.message || "unknown");
    }
}

async function compareVersion(planId) {
    if (state.compareMode && state.compareVersionId === planId) {
        closeDiff();
        return;
    }
    try {
        const result = await request(`/api/v1/workflow-runs/${state.workflowRunId}/custom-story-plan/versions/${planId}`);
        state.compareMode = true;
        state.compareVersionId = planId;
        state.compareVersionPlan = result.plan;
        renderDiff(result);
    } catch (error) {
        el("edit-status").textContent = "Compare failed: " + (error.message || "unknown");
    }
}

function closeDiff() {
    state.compareMode = false;
    state.compareVersionId = null;
    state.compareVersionPlan = null;
    el("diff-panel").hidden = true;
    renderVersionList();
}

function renderDiff(result) {
    const currentBeats = (state.customPlan && state.customPlan.beats) ? state.customPlan.beats : [];
    const savedBeats = (result.plan && result.plan.beats) ? result.plan.beats : [];
    const currentSnapshot = JSON.parse(JSON.stringify(currentBeats));
    const roles = ["HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING"];

    let totalAdded = 0, totalRemoved = 0, totalModified = 0, totalUnchanged = 0;
    const diffBeats = [];

    roles.forEach(role => {
        const curBeat = currentSnapshot.find(b => b.role === role);
        const savBeat = savedBeats.find(b => b.role === role);
        if (!curBeat && !savBeat) return;
        const curShots = (curBeat && curBeat.shots) ? curBeat.shots : [];
        const savShots = (savBeat && savBeat.shots) ? savBeat.shots : [];
        const curShotIds = new Set(curShots.map(s => s.shotId));
        const savShotIds = new Set(savShots.map(s => s.shotId));

        const shotDiffs = [];
        savShots.forEach(s => { if (!curShotIds.has(s.shotId)) shotDiffs.push({ type: "removed", shot: s }); });
        curShots.forEach(s => { if (!savShotIds.has(s.shotId)) { shotDiffs.push({ type: "added", shot: s }); totalAdded++; } });
        savShots.forEach((s, savIdx) => {
            if (curShotIds.has(s.shotId)) {
                const curIdx = curShots.findIndex(c => c.shotId === s.shotId);
                const cur = curIdx >= 0 ? curShots[curIdx] : null;
                if (cur && (cur.sourceInMs !== s.sourceInMs || cur.sourceOutMs !== s.sourceOutMs || cur.rank !== s.rank || curIdx !== savIdx)) {
                    shotDiffs.push({ type: "modified", shot: s, curShot: cur });
                    totalModified++;
                } else {
                    totalUnchanged++;
                }
            }
        });
        totalRemoved += shotDiffs.filter(d => d.type === "removed").length;
        if (!curBeat && savBeat) diffBeats.push({ type: "removed", role: role, shotDiffs: shotDiffs });
        else if (curBeat && !savBeat) diffBeats.push({ type: "added", role: role, shotDiffs: shotDiffs });
        else if (shotDiffs.length) diffBeats.push({ type: "modified", role: role, shotDiffs: shotDiffs });
    });

    el("diff-version-name").textContent = result.versionName || "Unnamed";
    el("diff-panel").hidden = false;

    el("diff-summary").replaceChildren(
        createDiffSummaryItem("added", totalAdded, "Added"),
        createDiffSummaryItem("removed", totalRemoved, "Removed"),
        createDiffSummaryItem("modified", totalModified, "Modified"),
        createDiffSummaryItem("unchanged", totalUnchanged, "Unchanged")
    );

    el("diff-list").replaceChildren(...diffBeats.map(db => {
        const beatDiv = document.createElement("div");
        beatDiv.className = "diff-beat diff-beat-" + db.type;
        const header = document.createElement("div");
        header.className = "diff-beat-header";
        const typeLabel = db.type === "added" ? "+ADDED" : db.type === "removed" ? "-REMOVED" : "~MODIFIED";
        header.innerHTML = `<span class="role-label">${db.role}</span> <small>${typeLabel}</small>`;
        beatDiv.appendChild(header);
        db.shotDiffs.forEach(sd => {
            const srow = document.createElement("div");
            srow.className = "diff-shot-row diff-shot-" + sd.type;
            const asset = state.assets.find(a => a.id === sd.shot.sourceAssetId);
            const label = asset ? asset.fileName : (sd.shot.sourceAssetId || "?").slice(0, 8);
            const dur = sd.shot.selectedDurationMs || (sd.shot.sourceOutMs - sd.shot.sourceInMs) || 0;
            srow.innerHTML = `<code>#${sd.shot.rank || "?"}</code> ${label} ${(dur / 1000).toFixed(1)}s`;
            beatDiv.appendChild(srow);
        });
        return beatDiv;
    }));
}

function createDiffSummaryItem(cls, count, label) {
    const item = document.createElement("div");
    item.className = "diff-summary-item diff-" + cls;
    item.innerHTML = `<span>${count}</span>${label}`;
    return item;
}

async function deleteVersion(planId) {
    if (!confirm("Delete this version? This cannot be undone.")) return;
    try {
        await request(`/api/v1/workflow-runs/${state.workflowRunId}/custom-story-plan/versions/${planId}`, {
            method: "DELETE"
        });
        state.versions = state.versions.filter(v => v.id !== planId);
        renderVersionList();
    } catch (error) {
        el("edit-status").textContent = "Delete failed: " + (error.message || "unknown");
    }
}
