const DEMO_STORAGE_KEY = "socialguard.presentation.batchIds.v1";
const CLASSIFICATIONS = ["OK", "SPAM", "VIOLENT", "SEXUAL"];
const BATCH_STATUSES = ["PENDING", "PROCESSING", "COMPLETED", "FAILED"];
const POLL_INTERVAL_MS = 5000;
const MAX_VISIBLE_BATCHES = 30;

function formatDuration(milliseconds) {
    if (!Number.isFinite(milliseconds) || milliseconds < 0) return "—";
    const seconds = Math.round(milliseconds / 1000);
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    if (minutes < 60) return remainingSeconds ? `${minutes}m ${remainingSeconds}s` : `${minutes}m`;
    const hours = Math.floor(minutes / 60);
    const remainingMinutes = minutes % 60;
    return remainingMinutes ? `${hours}h ${remainingMinutes}m` : `${hours}h`;
}

function summarizeBatches(batches) {
    if (!Array.isArray(batches)) throw new TypeError("A API não retornou uma lista de lotes.");
    const summary = {
        batches: batches.length,
        statuses: { PENDING: 0, PROCESSING: 0, COMPLETED: 0, FAILED: 0 },
        comments: 0,
        classified: 0,
        classifications: { OK: 0, SPAM: 0, VIOLENT: 0, SEXUAL: 0 },
        averageDurationMs: null,
    };
    const completedDurations = [];

    for (const batch of batches) {
        if (!batch || !BATCH_STATUSES.includes(batch.status) || !Array.isArray(batch.comments)) {
            throw new TypeError("A API retornou um lote com formato inválido.");
        }
        summary.statuses[batch.status]++;
        summary.comments += batch.comments.length;
        for (const comment of batch.comments) {
            if (!comment || comment.inferenceResult == null) continue;
            if (!CLASSIFICATIONS.includes(comment.inferenceResult)) {
                throw new TypeError("A API retornou uma classificação desconhecida.");
            }
            summary.classified++;
            summary.classifications[comment.inferenceResult]++;
        }
        if (batch.status === "COMPLETED" && batch.createdAt && batch.completedAt) {
            const duration = Date.parse(batch.completedAt) - Date.parse(batch.createdAt);
            if (Number.isFinite(duration) && duration >= 0) completedDurations.push(duration);
        }
    }

    if (completedDurations.length) {
        summary.averageDurationMs = completedDurations.reduce((total, duration) => total + duration, 0)
            / completedDurations.length;
    }
    return summary;
}

function parseCircuitBreakerMetrics(text, circuitName = "openai") {
    if (typeof text !== "string") throw new TypeError("As métricas do OpenMock não estão em texto.");
    const metricPattern = /^resilience4j_circuitbreaker_state\{([^}]*)\}\s+([0-9.eE+-]+)$/;
    for (const line of text.split(/\r?\n/)) {
        const match = line.trim().match(metricPattern);
        if (!match) continue;
        const labels = Object.fromEntries(
            [...match[1].matchAll(/([a-zA-Z_][a-zA-Z0-9_]*)="([^"]*)"/g)].map(([, key, value]) => [key, value]),
        );
        if (labels.name === circuitName && Number(match[2]) === 1 && labels.state) return labels.state;
    }
    return null;
}

function makeDemoPayload(companyId, timestamp = Date.now()) {
    const examples = [
        ["A equipe respondeu rápido e resolveu meu problema. Obrigado pelo atendimento!", "cliente"],
        ["Confira novidades e dicas no nosso perfil oficial esta semana.", "comunidade"],
        ["Ganhe um prêmio agora! Acesse este link e informe seus dados para receber.", "alerta"],
        ["O produto chegou antes do prazo e veio bem embalado.", "cliente"],
        ["A publicação desta campanha alcançou bastante gente hoje.", "comunidade"],
    ];
    return {
        companyId,
        comments: examples.map(([body, name], index) => ({
            postId: `demo-${timestamp}`,
            commentId: `${timestamp}-${index + 1}`,
            name: name === "cliente" ? "Conta de demonstração" : "Perfil de demonstração",
            body,
        })),
    };
}

const MAX_UPLOAD_BYTES = 1024 * 1024;
const ISO_LOCAL_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?$/;
const OPTIONAL_COMMENT_STRINGS = ["postId", "commentId", "name", "email"];

function validateBatchJson(text) {
    const result = { ok: false, total: 0, validCount: 0, invalidCount: 0, globalErrors: [], elements: [], payload: null };
    let data;
    try {
        data = JSON.parse(text);
    } catch (error) {
        result.globalErrors.push(`JSON malformado: ${error.message}`);
        return result;
    }
    if (data === null || typeof data !== "object" || Array.isArray(data)) {
        result.globalErrors.push("A raiz do arquivo deve ser um objeto com companyId e comments.");
        return result;
    }
    if (typeof data.companyId !== "string" || !data.companyId.trim()) {
        result.globalErrors.push("Propriedade 'companyId' ausente, vazia ou não textual.");
    }
    if (data.llmModel !== undefined && data.llmModel !== null && typeof data.llmModel !== "string") {
        result.globalErrors.push("Propriedade 'llmModel' deve ser texto.");
    }
    if (!Array.isArray(data.comments)) {
        result.globalErrors.push("Propriedade 'comments' ausente ou não é uma lista.");
    } else if (data.comments.length === 0) {
        result.globalErrors.push("A lista 'comments' está vazia; envie ao menos um comentário.");
    } else {
        const seenIds = new Map();
        data.comments.forEach((comment, index) => {
            const errors = [];
            if (comment === null || typeof comment !== "object" || Array.isArray(comment)) {
                errors.push("O elemento deve ser um objeto.");
            } else {
                if (typeof comment.body !== "string" || !comment.body.trim()) {
                    errors.push("Propriedade 'body' ausente, vazia ou não textual.");
                }
                for (const key of OPTIONAL_COMMENT_STRINGS) {
                    if (comment[key] !== undefined && comment[key] !== null && typeof comment[key] !== "string") {
                        errors.push(`Propriedade '${key}' deve ser texto.`);
                    }
                }
                for (const key of ["createdAt", "updatedAt"]) {
                    const value = comment[key];
                    if (value !== undefined && value !== null
                        && (typeof value !== "string" || !ISO_LOCAL_DATE_TIME.test(value) || Number.isNaN(Date.parse(value)))) {
                        errors.push(`Propriedade '${key}' deve estar no formato AAAA-MM-DDThh:mm:ss.`);
                    }
                }
                if ("inferenceResult" in comment) {
                    errors.push("Propriedade 'inferenceResult' não deve ser enviada; ela é gerada pelo OpenMock.");
                }
                if (typeof comment.commentId === "string" && comment.commentId) {
                    if (seenIds.has(comment.commentId)) {
                        errors.push(`'commentId' duplicado (já usado no elemento ${seenIds.get(comment.commentId) + 1}).`);
                    } else {
                        seenIds.set(comment.commentId, index);
                    }
                }
            }
            result.elements.push({ index, errors });
        });
        result.total = data.comments.length;
        result.invalidCount = result.elements.filter(element => element.errors.length).length;
        result.validCount = result.total - result.invalidCount;
    }
    result.ok = !result.globalErrors.length && result.total > 0 && result.invalidCount === 0;
    if (result.ok) result.payload = data;
    return result;
}

globalThis.SocialGuardDemo = Object.freeze({
    validateBatchJson,
    MAX_UPLOAD_BYTES,
    formatDuration,
    summarizeBatches,
    parseCircuitBreakerMetrics,
    makeDemoPayload,
});

if (typeof document !== "undefined") {
    const $ = id => document.getElementById(id);
    const state = { batches: [], demoIds: [], refreshRunning: false, pollTimer: null };
    const numberFormatter = new Intl.NumberFormat("pt-BR");
    const usdFormatter = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
    const statusLabels = {
        PENDING: "Pendente",
        PROCESSING: "Em processamento",
        COMPLETED: "Concluído",
        FAILED: "Falhou",
    };
    const classificationLabels = {
        OK: "OK",
        SPAM: "Spam",
        VIOLENT: "Violento",
        SEXUAL: "Sexual",
    };

    function setActionMessage(message, kind = "info") {
        const label = $("action-status");
        label.textContent = message;
        label.dataset.state = kind;
    }

    function setServiceState(id, stateName, label, messageId, message) {
        const badge = $(id);
        badge.dataset.state = stateName;
        badge.textContent = label;
        $(messageId).textContent = message;
    }

    async function readJson(path, options = {}) {
        const response = await fetch(path, { cache: "no-store", ...options });
        if (!response.ok) {
            const detail = (await response.text()).trim().slice(0, 240);
            throw new Error(`HTTP ${response.status}${detail ? `: ${detail}` : ""}`);
        }
        if (response.status === 204) return null;
        try {
            return await response.json();
        } catch (error) {
            if (error instanceof SyntaxError) throw new Error("O serviço retornou JSON inválido.");
            throw error;
        }
    }

    async function checkApiHealth() {
        try {
            const response = await fetch("/health", { cache: "no-store" });
            let health;
            try {
                health = await response.json();
            } catch (error) {
                if (error instanceof SyntaxError) throw new Error(`HTTP ${response.status}: resposta de saúde inválida.`);
                throw error;
            }
            if (response.ok && health && health.status === "UP") {
                setServiceState("api-health", "up", "Disponível", "api-health-message", "API e dependências monitoradas respondendo normalmente.");
            } else {
                const status = health && typeof health.status === "string" ? health.status : `HTTP ${response.status}`;
                setServiceState("api-health", "down", "Indisponível", "api-health-message", `A API informou ${status}. Verifique MySQL e RabbitMQ.`);
            }
        } catch (error) {
            setServiceState("api-health", "down", "Sem conexão", "api-health-message", `Não foi possível consultar a API: ${error.message}`);
            console.error("Falha ao verificar a API SocialGuard:", error);
        }
    }

    async function checkOpenMockHealth() {
        try {
            const response = await fetch("/openmock/health", { cache: "no-store" });
            if (response.status === 401 || response.status === 403) {
                setServiceState("openmock-health", "limited", "Protegido", "openmock-health-message", `OpenMock respondeu HTTP ${response.status}; o Actuator exige autenticação. Acompanhe o consumidor pelos estados dos lotes.`);
                return;
            }
            if (!response.ok) {
                setServiceState("openmock-health", "down", "Indisponível", "openmock-health-message", `O endpoint do OpenMock respondeu HTTP ${response.status}. Verifique se o consumidor está iniciado na porta 8083.`);
                return;
            }
            const health = await response.json();
            if (health && health.status === "UP") {
                setServiceState("openmock-health", "up", "Disponível", "openmock-health-message", "Actuator do consumidor respondeu com status UP.");
            } else {
                setServiceState("openmock-health", "down", "Indisponível", "openmock-health-message", `O OpenMock informou ${health && health.status ? health.status : "um status inválido"}.`);
            }
        } catch (error) {
            setServiceState("openmock-health", "down", "Sem conexão", "openmock-health-message", `Não foi possível alcançar o OpenMock em localhost:8083: ${error.message}`);
            console.error("Falha ao verificar o OpenMock:", error);
        }
    }

    async function checkCircuitBreaker() {
        const badge = $("breaker-state");
        try {
            const response = await fetch("/openmock/prometheus", { cache: "no-store" });
            if (response.status === 401 || response.status === 403) {
                badge.dataset.state = "limited";
                badge.textContent = "Protegido";
                badge.title = `Métricas do OpenMock protegidas (HTTP ${response.status}).`;
                return;
            }
            if (!response.ok) {
                badge.dataset.state = "limited";
                badge.textContent = `HTTP ${response.status}`;
                badge.title = "Não foi possível consultar as métricas do circuit breaker.";
                return;
            }
            const circuitState = SocialGuardDemo.parseCircuitBreakerMetrics(await response.text());
            if (!circuitState) {
                badge.dataset.state = "limited";
                badge.textContent = "Sem métrica";
                badge.title = "A métrica do circuit breaker openai não está exposta pelo OpenMock.";
                return;
            }
            const labels = { closed: "Fechado", open: "Aberto", half_open: "Meio aberto" };
            badge.dataset.state = circuitState === "closed" ? "up" : circuitState === "open" ? "down" : "limited";
            badge.textContent = labels[circuitState] || circuitState;
            badge.title = `Estado real do circuit breaker openai: ${circuitState}.`;
        } catch (error) {
            badge.dataset.state = "down";
            badge.textContent = "Indisponível";
            badge.title = `Não foi possível ler as métricas do OpenMock: ${error.message}`;
            console.error("Falha ao consultar o circuit breaker do OpenMock:", error);
        }
    }

    function readDemoIds() {
        try {
            const stored = localStorage.getItem(DEMO_STORAGE_KEY);
            if (stored === null) return [];
            const ids = JSON.parse(stored);
            if (!Array.isArray(ids) || ids.some(id => typeof id !== "string")) {
                throw new Error("A lista local de lotes de apresentação está inválida.");
            }
            return [...new Set(ids)];
        } catch (error) {
            console.error("Não foi possível ler os lotes de apresentação deste navegador:", error);
            setActionMessage("Não foi possível ler a lista local de lotes de apresentação. Os lotes existentes não serão removidos automaticamente.", "error");
            return [];
        }
    }

    function persistDemoIds() {
        try {
            localStorage.setItem(DEMO_STORAGE_KEY, JSON.stringify(state.demoIds));
            return true;
        } catch (error) {
            console.error("Não foi possível salvar os IDs dos lotes de apresentação:", error);
            return false;
        }
    }

    function formatDate(value) {
        if (!value) return "—";
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" });
    }

    function batchDuration(batch) {
        if (batch.status === "COMPLETED" && batch.createdAt && batch.completedAt) {
            return SocialGuardDemo.formatDuration(Date.parse(batch.completedAt) - Date.parse(batch.createdAt));
        }
        if (batch.status === "PROCESSING" && batch.createdAt) {
            return SocialGuardDemo.formatDuration(Date.now() - Date.parse(batch.createdAt));
        }
        if (batch.status === "PENDING") return "Na fila";
        return "—";
    }

    function makeCell(text, className) {
        const cell = document.createElement("td");
        if (className) cell.className = className;
        cell.textContent = text;
        return cell;
    }

    function renderBatches() {
        const list = $("batch-list");
        list.replaceChildren();
        const filter = $("status-filter").value;
        const visible = state.batches
            .filter(batch => !filter || batch.status === filter)
            .slice(0, MAX_VISIBLE_BATCHES);

        for (const batch of visible) {
            const row = document.createElement("tr");
            const idCell = document.createElement("td");
            idCell.className = "batch-identity";
            const company = document.createElement("strong");
            company.textContent = batch.companyId || "Sem organização";
            const id = document.createElement("span");
            id.textContent = batch.batchId;
            idCell.append(company, id);
            row.append(idCell);

            const status = document.createElement("td");
            const badge = document.createElement("span");
            badge.className = "status-badge";
            badge.dataset.state = batch.status.toLowerCase();
            badge.textContent = statusLabels[batch.status];
            status.append(badge);
            row.append(status);
            row.append(makeCell(numberFormatter.format(batch.comments.length)));
            row.append(makeCell(batchDuration(batch)));
            row.append(makeCell(formatDate(batch.createdAt)));

            const action = document.createElement("td");
            const details = document.createElement("button");
            details.type = "button";
            details.className = "detail-button";
            details.textContent = "Detalhes";
            details.setAttribute("aria-label", `Ver detalhes do lote ${batch.batchId}`);
            details.addEventListener("click", () => renderBatchDetail(batch));
            action.append(details);
            row.append(action);
            list.append(row);
        }

        $("batch-empty").hidden = visible.length > 0;
        $("cleanup-demo-batches").disabled = state.demoIds.length === 0;
        if (!visible.length) $("batch-detail").hidden = true;
    }

    function renderBatchDetail(batch) {
        const panel = $("batch-detail");
        panel.replaceChildren();
        const heading = document.createElement("div");
        heading.className = "detail-heading";
        const title = document.createElement("div");
        const label = document.createElement("p");
        label.className = "eyebrow";
        label.textContent = "DETALHES DO LOTE";
        const name = document.createElement("h3");
        name.textContent = batch.companyId || "Lote SocialGuard";
        const id = document.createElement("p");
        id.className = "detail-id";
        id.textContent = batch.batchId;
        title.append(label, name, id);
        const badge = document.createElement("span");
        badge.className = "status-badge";
        badge.dataset.state = batch.status.toLowerCase();
        badge.textContent = statusLabels[batch.status];
        heading.append(title, badge);
        panel.append(heading);

        const commentList = document.createElement("ul");
        commentList.className = "comment-list";
        for (const comment of batch.comments) {
            const item = document.createElement("li");
            const body = document.createElement("p");
            body.textContent = comment.body;
            const classification = document.createElement("span");
            const result = comment.inferenceResult;
            classification.className = "comment-result";
            classification.dataset.state = result ? result.toLowerCase() : "pending";
            classification.textContent = result ? classificationLabels[result] : "Aguardando classificação";
            item.append(body, classification);
            commentList.append(item);
        }
        panel.append(commentList);
        panel.hidden = false;
        panel.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    function updateMetrics(batches) {
        const summary = SocialGuardDemo.summarizeBatches(batches);
        $("metric-batches").textContent = numberFormatter.format(summary.batches);
        $("metric-batch-breakdown").textContent =
            `${summary.statuses.COMPLETED} concluídos · ${summary.statuses.PROCESSING} em processamento · ${summary.statuses.PENDING} pendentes · ${summary.statuses.FAILED} falhas`;
        $("metric-comments").textContent = numberFormatter.format(summary.comments);
        $("metric-classified").textContent = `${numberFormatter.format(summary.classified)} classificados`;
        $("metric-latency").textContent = summary.averageDurationMs === null
            ? "—"
            : SocialGuardDemo.formatDuration(summary.averageDurationMs);
        for (const classification of CLASSIFICATIONS) {
            $(`count-${classification.toLowerCase()}`).textContent =
                numberFormatter.format(summary.classifications[classification]);
        }
    }

    function updateSavings(report) {
        if (!report || !Number.isFinite(Number(report.totalTokensSaved))
            || !Number.isFinite(Number(report.percentageTokensSaved))
            || !Number.isFinite(Number(report.totalCostSavedUsd))) {
            throw new TypeError("A API retornou um relatório de economia incompleto.");
        }
        $("metric-tokens-saved").textContent = numberFormatter.format(report.totalTokensSaved);
        $("metric-tokens-percent").textContent = `${Number(report.percentageTokensSaved).toLocaleString("pt-BR")}% do cenário-base`;
        $("metric-cost-saved").textContent = usdFormatter.format(Number(report.totalCostSavedUsd));
    }

    async function refreshDashboard(silent = false) {
        if (state.refreshRunning) return;
        state.refreshRunning = true;
        if (!silent) $("refresh-dashboard").disabled = true;
        $("batch-error").hidden = true;

        const [apiHealth, openMockHealth, circuitBreaker, batchesResult, savingsResult] = await Promise.allSettled([
            checkApiHealth(),
            checkOpenMockHealth(),
            checkCircuitBreaker(),
            readJson("/api/v1/batches"),
            readJson("/api/v1/tokens/reports/savings"),
        ]);
        if (circuitBreaker.status === "rejected") {
            console.error("Falha ao consultar o circuit breaker do OpenMock:", circuitBreaker.reason);
        }

        if (batchesResult.status === "fulfilled") {
            try {
                state.batches = batchesResult.value;
                updateMetrics(state.batches);
                renderBatches();
            } catch (error) {
                $("batch-error").textContent = `Não foi possível apresentar os lotes: ${error.message}`;
                $("batch-error").hidden = false;
                console.error("Resposta inválida ao listar os lotes:", error);
            }
        } else {
            $("batch-error").textContent = `Falha ao carregar lotes: ${batchesResult.reason.message}`;
            $("batch-error").hidden = false;
            console.error("Falha ao carregar os lotes:", batchesResult.reason);
        }

        if (savingsResult.status === "fulfilled") {
            try {
                updateSavings(savingsResult.value);
            } catch (error) {
                $("metric-tokens-saved").textContent = "—";
                $("metric-tokens-percent").textContent = "Relatório indisponível";
                $("metric-cost-saved").textContent = "—";
                console.error("Resposta inválida do relatório de tokens:", error);
            }
        } else {
            $("metric-tokens-saved").textContent = "—";
            $("metric-tokens-percent").textContent = "Relatório indisponível";
            $("metric-cost-saved").textContent = "—";
            console.error("Falha ao carregar o relatório de tokens:", savingsResult.reason);
        }

        if (apiHealth.status === "rejected" || openMockHealth.status === "rejected") {
            console.error("Falha ao consultar o diagnóstico de serviços.");
        }
        $("updated-at").textContent = `Atualizado às ${new Date().toLocaleTimeString("pt-BR")}`;
        $("health-checked").textContent = `Verificado às ${new Date().toLocaleTimeString("pt-BR")}`;
        state.refreshRunning = false;
        $("refresh-dashboard").disabled = false;
        schedulePolling();
    }

    function schedulePolling() {
        if (state.pollTimer !== null) clearTimeout(state.pollTimer);
        const active = state.batches.some(batch => batch.status === "PENDING" || batch.status === "PROCESSING");
        if (active) state.pollTimer = setTimeout(() => refreshDashboard(true), POLL_INTERVAL_MS);
        else state.pollTimer = null;
    }

    async function createDemoBatch() {
        const companyId = $("company-id").value.trim();
        if (!companyId) {
            setActionMessage("Informe o identificador da organização antes de enviar.", "error");
            $("company-id").focus();
            return;
        }
        await submitBatch(SocialGuardDemo.makeDemoPayload(companyId));
    }

    async function submitBatch(payload) {
        const buttons = [$("create-demo-batch"), $("confirm-upload")];
        buttons.forEach(button => { button.disabled = true; });
        setActionMessage("Criando lote e publicando o identificador na fila…", "info");
        try {
            const created = await readJson("/api/v1/batches", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload),
            });
            if (!created || typeof created.batchId !== "string" || !Array.isArray(created.comments)) {
                throw new Error("A API aceitou a solicitação, mas retornou um lote em formato inválido.");
            }
            state.demoIds = [...new Set([...state.demoIds, created.batchId])];
            const tracked = persistDemoIds();
            state.batches = [created, ...state.batches.filter(batch => batch.batchId !== created.batchId)];
            updateMetrics(state.batches);
            renderBatches();
            setActionMessage(
                tracked
                    ? `Lote ${created.batchId} criado. Aguardando o OpenMock processar os comentários.`
                    : `Lote ${created.batchId} criado, mas não foi possível salvar seu identificador neste navegador; a limpeza seletiva não estará disponível.`,
                tracked ? "success" : "error",
            );
            await refreshDashboard(true);
            $("lotes").scrollIntoView({ behavior: "smooth", block: "start" });
        } catch (error) {
            setActionMessage(`Não foi possível criar o lote: ${error.message}. Confirme se SocialGuard, RabbitMQ e OpenMock estão configurados.`, "error");
            console.error("Falha ao criar lote de demonstração:", error);
        } finally {
            buttons.forEach(button => { button.disabled = false; });
        }
    }

    let validatedUpload = null;

    function resetUpload() {
        validatedUpload = null;
        $("json-file").value = "";
        $("upload-name").textContent = "Nenhum arquivo anexado.";
    }

    function closeUploadDialog() {
        const dialog = $("upload-dialog");
        if (dialog.open) dialog.close();
    }

    function showValidation(fileName, result) {
        $("upload-dialog-title").textContent = result.ok ? "Arquivo válido" : "Arquivo com erros";
        const summary = $("upload-summary");
        summary.dataset.state = result.ok ? "success" : "error";
        summary.textContent = result.ok
            ? `${fileName}: todos os ${result.total} elementos estão corretos e prontos para envio.`
            : `${fileName}: o anexo foi impedido. Corrija os erros e anexe novamente.`;
        $("upload-total").textContent = String(result.total);
        $("upload-valid").textContent = String(result.validCount);
        $("upload-invalid").textContent = String(result.invalidCount);
        const list = $("upload-errors");
        list.replaceChildren();
        const addItem = (label, messages) => {
            const item = document.createElement("li");
            const strong = document.createElement("strong");
            strong.textContent = label;
            item.append(strong);
            const sub = document.createElement("ul");
            messages.forEach(message => {
                const line = document.createElement("li");
                line.textContent = message;
                sub.append(line);
            });
            item.append(sub);
            list.append(item);
        };
        if (result.globalErrors.length) addItem("Estrutura do arquivo", result.globalErrors);
        result.elements.filter(element => element.errors.length)
            .forEach(element => addItem(`Elemento ${element.index + 1}`, element.errors));
        list.hidden = result.ok;
        $("confirm-upload").hidden = !result.ok;
        $("confirm-upload").textContent = `Enviar ${result.total} comentário(s)`;
        const dialog = $("upload-dialog");
        if (!dialog.open) dialog.showModal();
    }

    async function handleFileSelected() {
        const file = $("json-file").files[0];
        if (!file) return;
        let result;
        if (file.size > SocialGuardDemo.MAX_UPLOAD_BYTES) {
            result = { ok: false, total: 0, validCount: 0, invalidCount: 0, elements: [],
                globalErrors: ["O arquivo excede o limite de 1 MB."] };
        } else {
            try {
                result = SocialGuardDemo.validateBatchJson(await file.text());
            } catch (error) {
                result = { ok: false, total: 0, validCount: 0, invalidCount: 0, elements: [],
                    globalErrors: [`Não foi possível ler o arquivo: ${error.message}`] };
            }
        }
        if (result.ok) {
            validatedUpload = result.payload;
            $("upload-name").textContent = `${file.name} anexado (${result.total} comentários).`;
        } else {
            resetUpload();
        }
        showValidation(file.name, result);
    }

    async function confirmUpload() {
        if (!validatedUpload) return;
        const payload = validatedUpload;
        closeUploadDialog();
        await submitBatch(payload);
        resetUpload();
    }

    async function cleanupDemoBatches() {
        if (!state.demoIds.length) {
            setActionMessage("Este navegador não registra lotes próprios para remover.", "info");
            return;
        }
        const confirmed = window.confirm(
            `Remover ${state.demoIds.length} lote(s) de apresentação criado(s) neste navegador? Lotes de outras origens não serão afetados.`,
        );
        if (!confirmed) return;

        const button = $("cleanup-demo-batches");
        button.disabled = true;
        const removed = [];
        const failures = [];
        for (const id of state.demoIds) {
            try {
                await readJson(`/api/v1/batches/${encodeURIComponent(id)}`, { method: "DELETE" });
                removed.push(id);
            } catch (error) {
                if (error.message.startsWith("HTTP 404")) removed.push(id);
                else failures.push({ id, message: error.message });
            }
        }

        state.demoIds = state.demoIds.filter(id => !removed.includes(id));
        persistDemoIds();
        if (failures.length) {
            setActionMessage(
                `${removed.length} lote(s) removido(s); ${failures.length} não puderam ser removidos. ${failures[0].id}: ${failures[0].message}`,
                "error",
            );
        } else {
            setActionMessage(`${removed.length} lote(s) de apresentação removido(s).`, "success");
        }
        await refreshDashboard(true);
    }

    $("create-demo-batch").addEventListener("click", createDemoBatch);
    $("json-file").addEventListener("change", handleFileSelected);
    $("confirm-upload").addEventListener("click", confirmUpload);
    $("close-upload").addEventListener("click", () => { closeUploadDialog(); if (!validatedUpload) resetUpload(); });
    $("upload-dialog").addEventListener("cancel", () => { if (!validatedUpload) resetUpload(); });
    $("refresh-dashboard").addEventListener("click", () => refreshDashboard(false));
    $("cleanup-demo-batches").addEventListener("click", cleanupDemoBatches);
    $("status-filter").addEventListener("change", renderBatches);
    state.demoIds = readDemoIds();
    renderBatches();
    refreshDashboard(false);
}
