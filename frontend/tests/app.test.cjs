const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");
const { runInNewContext } = require("node:vm");

const source = readFileSync(join(__dirname, "..", "app.js"), "utf8");

function setup(fetch) {
    const elements = Object.fromEntries(
        ["refresh", "health-status", "health-message", "health-checked"].map(id =>
            [id, { dataset: {}, disabled: false, addEventListener(event, callback) { this.click = callback; } }])
    );
    let triggerTimeout;
    const context = {
        document: { getElementById: id => elements[id] },
        fetch,
        AbortController,
        setTimeout(callback) { triggerTimeout = callback; return 1; },
        clearTimeout() {},
        console: { error() {} },
    };
    runInNewContext(source, context);
    return {
        elements,
        expire: () => triggerTimeout(),
        settle: () => new Promise(resolve => setImmediate(resolve)),
    };
}

for (const [name, code, body, state, label] of [
    ["healthy", 200, { status: "UP" }, "up", "Disponível"],
    ["dependency down", 503, { status: "DOWN" }, "down", "Indisponível"],
    ["out of service", 503, { status: "OUT_OF_SERVICE" }, "down", "Indisponível"],
    ["unknown", 200, { status: "UNKNOWN" }, "error", "Saúde não confirmada"],
    ["inconsistent HTTP status", 503, { status: "UP" }, "error", "Saúde não confirmada"],
    ["missing status", 200, {}, "error", "Resposta inválida"],
    ["null body", 200, null, "error", "Resposta inválida"],
    ["wrong status type", 200, { status: 42 }, "error", "Resposta inválida"],
    ["proxy failure", 502, null, "error", "Falha na consulta"],
]) {
    test(name, async () => {
        const app = setup(async (url, options) => {
            assert.equal(url, "/health");
            assert.equal(options.cache, "no-store");
            return { status: code, ok: code === 200, json: async () => body };
        });
        await app.settle();
        assert.equal(app.elements["health-status"].dataset.state, state);
        assert.equal(app.elements["health-status"].textContent, label);
        assert.equal(app.elements.refresh.disabled, false);
        assert.match(app.elements["health-checked"].textContent, /Última verificação:/);
    });
}

test("invalid JSON", async () => {
    const app = setup(async () => ({ status: 200, ok: true, json: async () => { throw new SyntaxError(); } }));
    await app.settle();
    assert.equal(app.elements["health-status"].textContent, "Resposta inválida");
});

test("network failure", async () => {
    const app = setup(async () => { throw new TypeError("Network failure"); });
    await app.settle();
    assert.equal(app.elements["health-status"].textContent, "Falha de comunicação");
    assert.equal(app.elements.refresh.disabled, false);
});

test("timeout and concurrent clicks", async () => {
    let calls = 0;
    const app = setup((url, { signal }) => {
        calls++;
        return new Promise((resolve, reject) => signal.addEventListener("abort", () => reject(signal.reason)));
    });
    assert.equal(app.elements.refresh.disabled, true);
    await app.elements.refresh.click();
    assert.equal(calls, 1);
    app.expire();
    await app.settle();
    assert.equal(app.elements["health-status"].textContent, "Tempo esgotado");
    assert.equal(app.elements.refresh.disabled, false);
});

test("manual refresh recovers from a failure", async () => {
    let calls = 0;
    const app = setup(async () => {
        const code = ++calls === 1 ? 503 : 200;
        return { status: code, ok: code === 200, json: async () => ({ status: code === 200 ? "UP" : "DOWN" }) };
    });
    await app.settle();
    await app.elements.refresh.click();
    assert.equal(calls, 2);
    assert.equal(app.elements["health-status"].textContent, "Disponível");
});
