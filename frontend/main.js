/**
 * SpaceDock Main Module
 * Entry point. Initializes services, configures event listeners, and drives the UI lifecycle.
 */

import { ApiService } from './api.js';
import { UI } from './ui.js';

let activeDeploymentId = null;
let deploymentsData = [];
let reconnectTimer = null;

/**
 * Initialize application elements and bind handlers
 */
function init() {
    // 1. Load cached API Key from local storage
    const cachedKey = ApiService.getApiKey();
    if (cachedKey && UI.elements.apiKey) {
        UI.elements.apiKey.value = cachedKey;
    }

    // 2. Adjust Webhook guide URL dynamically based on current page address
    const webhookUrlEl = document.getElementById('webhook-url');
    if (webhookUrlEl && window.location.hostname && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
        webhookUrlEl.textContent = `http://${window.location.hostname}:8082/api/webhooks/github`;
    }

    // 3. Bind credentials input listener
    UI.elements.apiKey?.addEventListener('input', (e) => {
        ApiService.saveApiKey(e.target.value);
    });

    // 4. Bind Deploy button listener
    UI.elements.deployBtn?.addEventListener('click', handleDeploy);

    // 5. Bind Environment variable builder row delegation
    UI.elements.envVarsContainer?.addEventListener('click', (e) => {
        if (e.target.classList.contains('remove-env-btn')) {
            UI.removeEnvRow(e.target);
        }
    });

    // 6. Bind Webhook accordion guide toggle
    const accordionHeader = document.querySelector('.accordion-header');
    accordionHeader?.addEventListener('click', () => {
        UI.toggleWebhookAccordion();
    });

    // 7. Bind dynamic elements event delegation for Deployments List (Logs, Stop actions)
    UI.elements.deploymentsList?.addEventListener('click', handleDeploymentActions);

    // 8. Bind clipboard copy helpers
    const aside = document.querySelector('aside');
    aside?.addEventListener('click', (e) => {
        const copyBtn = e.target.closest('.copy-btn');
        if (copyBtn) {
            const targetId = copyBtn.getAttribute('data-copy');
            if (targetId) {
                e.preventDefault();
                e.stopPropagation();
                copyTextToClipboard(targetId);
            }
        }
    });

    // 9. Initial Websocket connection & deployments listing load
    connectWebSocket();
    refreshDeployments();

    // 10. Background deployments state polling (every 7 seconds)
    setInterval(refreshDeployments, 7000);
}

/**
 * Connect to SockJS / STOMP server with automatic retry logic
 */
function connectWebSocket() {
    UI.updateWebSocketStatus('connecting');

    ApiService.connectWebSocket(
        // onConnect
        () => {
            UI.updateWebSocketStatus('connected');
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            // Re-subscribe if logs console was focused
            if (activeDeploymentId) {
                subscribeToLogs(activeDeploymentId, getRepoUrlForId(activeDeploymentId));
            }
        },
        // onDisconnect / onError
        (error) => {
            UI.updateWebSocketStatus('disconnected');
            if (!reconnectTimer) {
                console.warn('⚠️ WebSocket disconnected. Retrying in 5 seconds...');
                reconnectTimer = setTimeout(connectWebSocket, 5000);
            }
        }
    );
}

/**
 * Refresh Deployments from DB and update UI list
 */
function refreshDeployments() {
    ApiService.fetchDeployments()
        .then(deployments => {
            deploymentsData = deployments;
            UI.renderDeployments(deployments, activeDeploymentId);

            // Update terminal log header status if active
            if (activeDeploymentId) {
                const current = deployments.find(d => d.id === activeDeploymentId);
                if (current) {
                    UI.updateConsoleHeader(UI.getRepoNameFromUrl(current.repoUrl), current.status);
                }
            }
        })
        .catch(err => {
            const container = UI.elements.deploymentsList;
            if (container) {
                container.innerHTML = `
                    <div style="color: var(--status-failed); text-align: center; padding: 20px; font-size: 13px;">
                        ⚠️ Error loading deployments: ${err.message}
                    </div>`;
            }
        });
}

/**
 * Handle triggering of a new deployment pipeline
 */
function handleDeploy() {
    const repoUrl = UI.elements.repoUrl?.value.trim();
    if (!repoUrl) {
        UI.showStatusMessage("Repository URL is required.", "error");
        return;
    }

    UI.toggleDeployBtnLoading(true);
    UI.showStatusMessage("");

    const envVars = UI.getEnvVars();

    ApiService.deploy(repoUrl, envVars)
        .then(deploymentId => {
            UI.showStatusMessage("Deployment successfully queued!", "success");
            
            // Reset input values
            if (UI.elements.repoUrl) UI.elements.repoUrl.value = '';
            if (UI.elements.envVarsContainer) UI.elements.envVarsContainer.innerHTML = '';

            // Shift focus and log subscription directly to the new deployment
            selectDeploymentLogs(deploymentId, repoUrl);
            refreshDeployments();
        })
        .catch(err => {
            UI.showStatusMessage(err.message, "error");
        })
        .finally(() => {
            UI.toggleDeployBtnLoading(false);
        });
}

/**
 * Delegated event handler for clicks inside the deployments list
 */
function handleDeploymentActions(e) {
    const target = e.target.closest('button[data-action]');
    if (!target) return;

    const action = target.getAttribute('data-action');
    const id = target.getAttribute('data-id');

    if (action === 'logs') {
        const repoUrl = target.getAttribute('data-repo');
        selectDeploymentLogs(id, repoUrl);
    } else if (action === 'stop') {
        stopDeployment(id);
    }
}

/**
 * Select a deployment, reset the terminal screen, and subscribe to WebSocket logs
 * @param {string} id Deployment UUID
 * @param {string} repoUrl Repository URL
 */
function selectDeploymentLogs(id, repoUrl) {
    activeDeploymentId = id;

    // Refresh display to render border highlight on active item
    UI.renderDeployments(deploymentsData, activeDeploymentId);

    // Clean logs UI
    UI.clearLogs();
    
    // Set headers
    const current = deploymentsData.find(d => d.id === id);
    const repoName = UI.getRepoNameFromUrl(repoUrl);
    UI.updateConsoleHeader(repoName, current ? current.status : 'QUEUED');

    subscribeToLogs(id, repoUrl);
}

/**
 * Connect the WebSocket log stream
 */
function subscribeToLogs(id, repoUrl) {
    ApiService.subscribeToLogs(id, (text, type) => {
        UI.appendLogLine(text, type);
    });
}

/**
 * Stop a running container and trigger state updates
 * @param {string} id Deployment UUID
 */
function stopDeployment(id) {
    UI.toggleStopBtnLoading(id, true);

    ApiService.stopDeployment(id)
        .then(() => {
            UI.showStatusMessage("Deployment stopped successfully.", "success");
            refreshDeployments();
        })
        .catch(err => {
            UI.showStatusMessage("Error stopping application: " + err.message, "error");
            UI.toggleStopBtnLoading(id, false);
        });
}

// --- Utilities ---
function getRepoUrlForId(id) {
    const found = deploymentsData.find(d => d.id === id);
    return found ? found.repoUrl : '';
}

function copyTextToClipboard(elementId) {
    const el = document.getElementById(elementId);
    if (!el) return;
    navigator.clipboard.writeText(el.textContent.trim())
        .then(() => {
            alert("Copied value to clipboard!");
        })
        .catch(err => {
            console.error("Unable to copy text: ", err);
        });
}

// Global hook to attach row creation
window.addEnvRow = () => UI.addEnvRow();
window.clearLogs = () => UI.clearLogs();
window.fetchDeployments = () => refreshDeployments();

// Bootstrap application on load
document.addEventListener('DOMContentLoaded', init);
export { selectDeploymentLogs, stopDeployment, refreshDeployments };
