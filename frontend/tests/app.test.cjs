const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");
const { runInNewContext } = require("node:vm");

const frontend = join(__dirname, "..");
const source = readFileSync(join(frontend, "app.js"), "utf8");

function loadHelpers() {
    const context = {};
    runInNewContext(source, context);
    return context.SocialGuardDemo;
}

class Element {
    constructor(id = "") {
        this.id = id;
        this.dataset = {};
        this.children = [];
        this.listeners = {};
        this.hidden = false;
        this.disabled = false;
        this.value = "";
        this.textContent = "";
        this.title = "";
    }

    addEventListener(event, callback) {
        this.listeners[event] = callback;
    }

    append(...children) {
        this.children.push(...children);
    }

    replaceChildren(...children) {
        this.children = [...children];
    }

    setAttribute(name, value) {
        this[name] = value;
    }

    scrollIntoView() {}
}

function makeBatch(batchId, status, createdAt, completedAt, results = []) {
    return {
        batchId,
        companyId: "demo",
        status,
        createdAt,
        completedAt,
        comments: results.map((inferenceResult, index) => ({
            id: index + 1,
            body: `Comentário ${index + 1}`,
            inferenceResult,
        })),
    };
}

function setupApp() {
    const ids = [
        "action-status", "api-health", "api-health-message", "batch-detail", "batch-empty", "batch-error",
        "batch-list", "breaker-state", "cleanup-demo-batches", "company-id", "create-demo-batch",
        "health-checked", "metric-batches", "metric-batch-breakdown", "metric-classified", "metric-comments",
        "metric-cost-saved", "metric-latency", "metric-tokens-percent", "metric-tokens-saved", "openmock-health",
        "openmock-health-message", "refresh-dashboard", "status-filter", "updated-at",
        "count-ok", "count-spam", "count-violent", "count-sexual", "demonstracao", "lotes",
        "json-file", "upload-name", "upload-dialog", "upload-dialog-title", "upload-summary", "upload-total",
        "upload-valid", "upload-invalid", "upload-errors", "confirm-upload", "close-upload",
    ];
    const elements = Object.fromEntries(ids.map(id => [id, new Element(id)]));
    elements["company-id"].value = "apresentacao-socialguard";

    const existing = makeBatch(
        "existing-batch", "COMPLETED", "2026-10-19T10:00:00", "2026-10-19T10:00:04", ["OK", "SPAM"],
    );
    const batches = [existing];
    const localValues = new Map();
    const calls = [];
    let nextId = 1;

    function response(status, body, text = "") {
        return {
            status,
            ok: status >= 200 && status < 300,
            async json() {
                if (body instanceof Error) throw body;
                return body;
            },
            async text() {
                return text || (typeof body === "string" ? body : "");
            },
        };
    }

    async function fetch(url, options = {}) {
        calls.push({ url, options });
        if (url === "/health") return response(200, { status: "UP" });
        if (url === "/openmock/health" || url === "/openmock/prometheus") return response(401, "Unauthorized", "Unauthorized");
        if (url === "/api/v1/batches" && options.method === "POST") {
            const request = JSON.parse(options.body);
            assert.equal(request.comments.length, 5);
            const batch = makeBatch(`demo-${nextId++}`, "PENDING", new Date().toISOString(), null, Array(5).fill(null));
            batch.companyId = request.companyId;
            batch.comments = request.comments.map((comment, index) => ({
                id: index + 1,
                body: comment.body,
                inferenceResult: null,
            }));
            batches.unshift(batch);
            return response(201, batch);
        }
        if (url === "/api/v1/batches" && !options.method) return response(200, [...batches]);
        if (url === "/api/v1/tokens/reports/savings") {
            return response(200, {
                totalTokensConsumed: 600,
                totalTokensSaved: 200,
                percentageTokensSaved: 25,
                totalCostUsd: 0.0012,
                totalCostSavedUsd: 0.0004,
            });
        }
        const deleteMatch = url.match(/^\/api\/v1\/batches\/([^/]+)$/);
        if (deleteMatch && options.method === "DELETE") {
            const id = decodeURIComponent(deleteMatch[1]);
            const index = batches.findIndex(batch => batch.batchId === id);
            if (index < 0) return response(404, "Not found");
            batches.splice(index, 1);
            return response(204, null);
        }
        throw new Error(`Unexpected request: ${options.method || "GET"} ${url}`);
    }

    const sandbox = {
        document: {
            getElementById: id => elements[id],
            createElement: () => new Element(),
        },
        fetch,
        localStorage: {
            getItem: key => localValues.get(key) ?? null,
            setItem: (key, value) => localValues.set(key, value),
        },
        window: { confirm: () => true },
        console: { error() {} },
        setTimeout,
        clearTimeout,
    };
    runInNewContext(source, sandbox);

    return {
        batches,
        calls,
        elements,
        localValues,
        async settle() {
            await new Promise(resolve => setImmediate(resolve));
            await new Promise(resolve => setImmediate(resolve));
        },
    };
}

test("formats durations in seconds, minutes, and hours", () => {
    const { formatDuration } = loadHelpers();
    assert.equal(formatDuration(0), "0s");
    assert.equal(formatDuration(59_000), "59s");
    assert.equal(formatDuration(60_000), "1m");
    assert.equal(formatDuration(61_000), "1m 1s");
    assert.equal(formatDuration(3_600_000), "1h");
    assert.equal(formatDuration(-1), "—");
});

test("summarizes lifecycle, real classifications, and average completed duration", () => {
    const summary = loadHelpers().summarizeBatches([
        makeBatch("done-1", "COMPLETED", "2026-10-19T10:00:00", "2026-10-19T10:00:04", ["OK", "SPAM"]),
        makeBatch("done-2", "COMPLETED", "2026-10-19T10:00:00", "2026-10-19T10:00:06", ["VIOLENT", "SEXUAL"]),
        makeBatch("working", "PROCESSING", "2026-10-19T10:00:00", null, [null]),
        makeBatch("pending", "PENDING", "2026-10-19T10:00:00", null, [null]),
        makeBatch("failed", "FAILED", "2026-10-19T10:00:00", "2026-10-19T10:00:02", [null]),
    ]);
    assert.equal(summary.batches, 5);
    assert.deepEqual({ ...summary.statuses }, { PENDING: 1, PROCESSING: 1, COMPLETED: 2, FAILED: 1 });
    assert.equal(summary.comments, 7);
    assert.equal(summary.classified, 4);
    assert.deepEqual({ ...summary.classifications }, { OK: 1, SPAM: 1, VIOLENT: 1, SEXUAL: 1 });
    assert.equal(summary.averageDurationMs, 5000);
});

test("rejects malformed batches and unknown classifications instead of reporting false metrics", () => {
    const { summarizeBatches } = loadHelpers();
    assert.throws(() => summarizeBatches({}), /lista de lotes/);
    assert.throws(() => summarizeBatches([{ status: "UNKNOWN", comments: [] }]), /formato inválido/);
    assert.throws(
        () => summarizeBatches([makeBatch("bad", "COMPLETED", null, null, ["OTHER"])]),
        /classificação desconhecida/,
    );
});

test("reads the active OpenMock circuit breaker state independent of label order", () => {
    const { parseCircuitBreakerMetrics } = loadHelpers();
    const metrics = [
        'resilience4j_circuitbreaker_state{name="other",state="open"} 1',
        'resilience4j_circuitbreaker_state{state="closed",name="openai"} 0',
        'resilience4j_circuitbreaker_state{state="open",name="openai"} 1',
    ].join("\n");
    assert.equal(parseCircuitBreakerMetrics(metrics), "open");
    assert.equal(parseCircuitBreakerMetrics('resilience4j_circuitbreaker_state{name="openai",state="closed"} 1'), "closed");
    assert.equal(parseCircuitBreakerMetrics("# no breaker metric"), null);
});

test("builds a five-comment demonstration payload for the requested organization", () => {
    const payload = loadHelpers().makeDemoPayload("company-demo", 123);
    assert.equal(payload.companyId, "company-demo");
    assert.equal(payload.comments.length, 5);
    assert.equal(payload.comments[0].postId, "demo-123");
    assert.equal(new Set(payload.comments.map(comment => comment.commentId)).size, 5);
    assert.ok(payload.comments.every(comment => comment.body.trim().length > 0));
});

test("validates the sample JSON files: the valid one passes, the faulty one is blocked", () => {
    const fs = require("node:fs");
    const path = require("node:path");
    const read = name => fs.readFileSync(path.join(__dirname, "..", "samples", name), "utf8");
    const helpers = loadHelpers();

    const good = helpers.validateBatchJson(read("lote-valido.json"));
    assert.equal(good.ok, true);
    assert.equal(good.total, 4);
    assert.equal(good.validCount, 4);

    const bad = helpers.validateBatchJson(read("lote-com-erros.json"));
    assert.equal(bad.ok, false);
    assert.equal(bad.total, 5);
    assert.equal(bad.invalidCount, 4);
    assert.equal(bad.validCount, 1);
    assert.equal(bad.payload, null);
});

test("rejects malformed JSON, missing companyId and empty comments", () => {
    const { validateBatchJson } = loadHelpers();
    assert.equal(validateBatchJson("{oops").ok, false);
    assert.equal(validateBatchJson("[]").ok, false);
    assert.equal(validateBatchJson('{"comments":[{"body":"x"}]}').ok, false);
    assert.equal(validateBatchJson('{"companyId":"a","comments":[]}').ok, false);
    assert.equal(validateBatchJson('{"companyId":"a","comments":[{"body":"x"}]}').ok, true);
});

test("attaching a faulty file shows errors and blocks sending; a valid file can be sent", async () => {
    const fs = require("node:fs");
    const path = require("node:path");
    const read = name => fs.readFileSync(path.join(__dirname, "..", "samples", name), "utf8");
    const app = setupApp();
    await app.settle();
    const dialog = app.elements["upload-dialog"];
    dialog.showModal = () => { dialog.open = true; };
    dialog.close = () => { dialog.open = false; };
    const input = app.elements["json-file"];

    input.files = [{ name: "lote-com-erros.json", size: 900, text: async () => read("lote-com-erros.json") }];
    await input.listeners.change();
    assert.equal(dialog.open, true);
    assert.equal(app.elements["upload-invalid"].textContent, "4");
    assert.equal(app.elements["confirm-upload"].hidden, true);
    assert.equal(app.elements["upload-name"].textContent, "Nenhum arquivo anexado.");
    await app.elements["confirm-upload"].listeners.click();
    assert.equal(app.calls.some(call => call.options.method === "POST"), false);

    input.files = [{ name: "lote-valido.json", size: 1100, text: async () => read("lote-valido.json") }];
    await input.listeners.change();
    assert.equal(app.elements["upload-total"].textContent, "4");
    assert.equal(app.elements["confirm-upload"].hidden, false);
    await app.elements["confirm-upload"].listeners.click();
    const post = app.calls.find(call => call.options.method === "POST");
    assert.equal(JSON.parse(post.options.body).comments.length, 4);
});

test("creates real API batches and cleanup only deletes this browser's demo IDs", async () => {
    const app = setupApp();
    await app.settle();
    assert.equal(app.elements["api-health"].textContent, "Disponível");
    assert.equal(app.elements["openmock-health"].textContent, "Protegido");
    assert.equal(app.elements["breaker-state"].textContent, "Protegido");
    assert.equal(app.elements["metric-tokens-saved"].textContent, "200");

    await app.elements["create-demo-batch"].listeners.click();
    assert.equal(app.batches.length, 2);
    assert.equal(app.elements["action-status"].dataset.state, "success");
    assert.match(app.elements["action-status"].textContent, /Aguardando o OpenMock/);
    const createCall = app.calls.find(call => call.url === "/api/v1/batches" && call.options.method === "POST");
    assert.ok(createCall);
    assert.equal(JSON.parse(createCall.options.body).comments.length, 5);
    assert.deepEqual(JSON.parse(app.localValues.get("socialguard.presentation.batchIds.v1")), ["demo-1"]);

    await app.elements["cleanup-demo-batches"].listeners.click();
    assert.deepEqual(app.batches.map(batch => batch.batchId), ["existing-batch"]);
    assert.deepEqual(
        app.calls.filter(call => call.options.method === "DELETE").map(call => call.url),
        ["/api/v1/batches/demo-1"],
    );
    assert.equal(app.elements["cleanup-demo-batches"].disabled, true);
});
