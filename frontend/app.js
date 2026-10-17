const refreshButton = document.getElementById("refresh");
const statusLabel = document.getElementById("health-status");
const messageLabel = document.getElementById("health-message");
const checkedLabel = document.getElementById("health-checked");

function showStatus(state, label, message) {
    statusLabel.dataset.state = state;
    statusLabel.textContent = label;
    messageLabel.textContent = message;
}

async function checkHealth() {
    if (refreshButton.disabled) return;

    refreshButton.disabled = true;
    showStatus("loading", "Consultando", "Verificando a saúde da API...");
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);

    try {
        const response = await fetch("/health", { signal: controller.signal, cache: "no-store" });
        if (response.status !== 200 && response.status !== 503) {
            showStatus("error", "Falha na consulta", `A consulta retornou HTTP ${response.status}. A API pode estar iniciando ou inacessível.`);
            return;
        }

        let health;
        try {
            health = await response.json();
        } catch (error) {
            if (controller.signal.aborted) throw error;
            showStatus("error", "Resposta inválida", "A API não retornou uma resposta de saúde em JSON válido.");
            return;
        }

        if (!health || typeof health.status !== "string" || !health.status.trim()) {
            showStatus("error", "Resposta inválida", "A resposta da API não contém um status de saúde válido.");
        } else if (response.ok && health.status === "UP") {
            showStatus("up", "Disponível", "A API e as dependências monitoradas estão saudáveis.");
        } else if (health.status === "DOWN" || health.status === "OUT_OF_SERVICE") {
            showStatus("down", "Indisponível", `A API informou ${health.status}. Uma ou mais dependências podem estar indisponíveis.`);
        } else {
            showStatus("error", "Saúde não confirmada", `A API informou ${health.status} (HTTP ${response.status}).`);
        }
    } catch (error) {
        showStatus("error", controller.signal.aborted ? "Tempo esgotado" : "Falha de comunicação",
            controller.signal.aborted
                ? "A consulta excedeu o limite de 8 segundos. Tente atualizar o status."
                : "Não foi possível consultar a API. Verifique a conexão e tente novamente.");
        console.error("Falha ao consultar a saúde da API:", error);
    } finally {
        clearTimeout(timeout);
        refreshButton.disabled = false;
        checkedLabel.textContent = `Última verificação: ${new Date().toLocaleString("pt-BR")}`;
    }
}

refreshButton.addEventListener("click", checkHealth);
checkHealth();
