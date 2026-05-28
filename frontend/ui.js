/**
 * SpaceDock UI Module
 * Encapsulates all DOM querying, template rendering, and DOM updates.
 */

export const UI = {
    // --- Cached DOM Getters to prevent stale references ---
    elements: {
        get apiKey() { return document.getElementById('api-key'); },
        get repoUrl() { return document.getElementById('repo-url'); },
        get envVarsContainer() { return document.getElementById('env-vars-container'); },
        get deployBtn() { return document.getElementById('deploy-btn'); },
        get deploymentsList() { return document.getElementById('deployments-list'); },
        get terminalScreen() { return document.getElementById('terminal-screen'); },
        get consoleTitle() { return document.getElementById('console-title'); },
        get consoleStatusBadge() { return document.getElementById('console-status-badge'); },
        get consoleStatusText() { return document.getElementById('console-status-text'); },
        get autoscrollChk() { return document.getElementById('autoscroll-chk'); },
        get statusMsg() { return document.getElementById('status-msg'); },
        get wsDot() { return document.getElementById('ws-dot'); },
        get wsText() { return document.getElementById('ws-text'); },
        get webhookAccordion() { return document.getElementById('webhook-accordion'); },
        get accordionArrow() { return document.getElementById('accordion-arrow'); }
    },

    /**
     * Update WebSocket connection status indicator
     * @param {'connected'|'disconnected'|'connecting'} state Connection state
     */
    updateWebSocketStatus(state) {
        const dot = this.elements.wsDot;
        const text = this.elements.wsText;
        if (!dot || !text) return;

        dot.className = 'status-dot';
        if (state === 'connected') {
            dot.className = 'status-dot connected';
            text.textContent = 'Connected';
        } else if (state === 'disconnected') {
            dot.className = 'status-dot disconnected';
            text.textContent = 'Disconnected';
        } else {
            text.textContent = 'Connecting...';
        }
    },

    /**
     * Render the active deployments list using a declarative template literal
     * @param {Array} deployments List of deployment objects
     * @param {string|null} activeId Current deployment log-streaming ID
     */
    renderDeployments(deployments, activeId) {
        const container = this.elements.deploymentsList;
        if (!container) return;

        if (!deployments || deployments.length === 0) {
            container.innerHTML = `
                <div style="color: var(--text-muted); text-align: center; padding: 20px; font-size: 13px;">
                    No applications deployed yet. Paste a repository URL above to get started!
                </div>`;
            return;
        }

        // Sort deployments: newer created dates first
        const sorted = [...deployments].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));

        const html = sorted.map(dep => {
            const repoName = this.getRepoNameFromUrl(dep.repoUrl);
            const cleanHash = dep.commitHash ? dep.commitHash.substring(0, 7) : 'no commit';
            const statusClass = dep.status ? dep.status.toLowerCase() : 'queued';
            const isRunning = dep.status === 'RUNNING';
            const isStopped = dep.status === 'STOPPED' || dep.status === 'FAILED';
            
            const localSubdomain = `${repoName.toLowerCase()}.localhost`;
            const liveLink = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
                ? `http://${localSubdomain}`
                : `http://${localSubdomain}:${window.location.port || '80'}`;

            const isSelected = activeId === dep.id;
            const borderStyle = isSelected ? 'style="border-color: var(--color-blue); background: rgba(0, 112, 243, 0.03);"' : '';

            return `
                <div class="deployment-item" ${borderStyle}>
                    <div class="deployment-info">
                        <div class="deployment-header-row">
                            <span class="deployment-repo">${repoName}</span>
                            <span class="deployment-badge badge-${statusClass}">
                                <span class="badge-dot"></span>
                                <span>${dep.status}</span>
                            </span>
                        </div>
                        <div class="deployment-meta">
                            <span>📅 ${this.formatDate(dep.createdAt)}</span>
                            <span class="deployment-hash">💻 git:${cleanHash}</span>
                            ${dep.portNumber ? `<span>🔌 port:${dep.portNumber}</span>` : ''}
                        </div>
                    </div>
                    <div class="deployment-actions">
                        ${isRunning ? `
                            <a href="${liveLink}" target="_blank">
                                <button class="btn-sm" style="background: rgba(0, 255, 135, 0.08); color: var(--status-running); border-color: rgba(0, 255, 135, 0.2);">
                                    🔗 Visit
                                </button>
                            </a>` : ''
                        }
                        <button class="btn-sm" data-action="logs" data-id="${dep.id}" data-repo="${dep.repoUrl}">
                            📜 Logs
                        </button>
                        ${!isStopped ? `
                            <button class="btn-danger btn-sm" id="stop-${dep.id}" data-action="stop" data-id="${dep.id}">
                                🛑 Stop
                            </button>` : ''
                        }
                    </div>
                </div>
            `;
        }).join('');

        container.innerHTML = html;
    },

    /**
     * Clear and set initial empty state for the terminal console
     */
    clearLogs() {
        const screen = this.elements.terminalScreen;
        if (!screen) return;

        screen.innerHTML = `
            <div class="empty-logs">
                <span class="empty-logs-icon">📡</span>
                <div>Ready to display logs. Select an application to begin streaming.</div>
            </div>`;
    },

    /**
     * Append a log line with custom coloring
     * @param {string} text Raw log line
     * @param {'error'|'success'|'info'|''} type Line category for coloring
     */
    appendLogLine(text, type) {
        const screen = this.elements.terminalScreen;
        if (!screen) return;

        const emptyLogs = screen.querySelector('.empty-logs');
        if (emptyLogs) {
            screen.innerHTML = '';
        }

        const lineDiv = document.createElement('div');
        lineDiv.className = `log-line ${type}`;
        lineDiv.textContent = text;
        screen.appendChild(lineDiv);

        // Auto-scroll check
        const autoscroll = this.elements.autoscrollChk ? this.elements.autoscrollChk.checked : true;
        if (autoscroll) {
            screen.scrollTop = screen.scrollHeight;
        }
    },

    /**
     * Update console terminal header title and status badge
     * @param {string} title Repository Name title
     * @param {string|null} status Active workload status
     */
    updateConsoleHeader(title, status) {
        const titleEl = this.elements.consoleTitle;
        const badge = this.elements.consoleStatusBadge;
        const label = this.elements.consoleStatusText;

        if (titleEl) {
            titleEl.textContent = title ? `${title} Logs` : 'Terminal Logs';
        }

        if (!badge || !label) return;

        if (!status) {
            badge.style.display = 'none';
        } else {
            badge.style.display = 'inline-flex';
            badge.className = `deployment-badge badge-${status.toLowerCase()}`;
            label.textContent = status;
        }
    },

    /**
     * Show operation alerts
     * @param {string} text Message
     * @param {'error'|'success'|''} type Status style
     */
    showStatusMessage(text, type = '') {
        const el = this.elements.statusMsg;
        if (!el) return;
        el.textContent = text;
        el.className = 'status-msg ' + type;
    },

    /**
     * Add a key-value row to env list builder
     */
    addEnvRow() {
        const container = this.elements.envVarsContainer;
        if (!container) return;

        const row = document.createElement('div');
        row.className = 'env-row';
        row.innerHTML = `
            <input type="text" class="env-key" placeholder="KEY" style="flex: 0.4;">
            <input type="text" class="env-value" placeholder="VALUE">
            <button type="button" class="remove-env-btn">✕</button>
        `;
        container.appendChild(row);
    },

    /**
     * Remove a key-value row with animation
     * @param {HTMLElement} btn The delete button element
     */
    removeEnvRow(btn) {
        const row = btn.closest('.env-row');
        if (!row) return;

        row.style.opacity = '0';
        row.style.transform = 'translateY(-4px)';
        setTimeout(() => {
            row.remove();
        }, 150);
    },

    /**
     * Get environment variables from input rows
     * @returns {Object} Key-value environment variable mapping
     */
    getEnvVars() {
        const envVars = {};
        const container = this.elements.envVarsContainer;
        if (!container) return envVars;

        container.querySelectorAll('.env-row').forEach(row => {
            const keyEl = row.querySelector('.env-key');
            const valEl = row.querySelector('.env-value');
            if (keyEl && valEl) {
                const key = keyEl.value.trim();
                const value = valEl.value.trim();
                if (key && value) {
                    envVars[key] = value;
                }
            }
        });
        return envVars;
    },

    /**
     * Toggle visibility of webhook documentation guide
     */
    toggleWebhookAccordion() {
        const content = this.elements.webhookAccordion;
        const arrow = this.elements.accordionArrow;
        if (!content || !arrow) return;

        if (content.classList.contains('open')) {
            content.classList.remove('open');
            arrow.textContent = '▼';
        } else {
            content.classList.add('open');
            arrow.textContent = '▲';
        }
    },

    /**
     * Toggle deploy button loading state spinner
     * @param {boolean} isLoading True if loading
     */
    toggleDeployBtnLoading(isLoading) {
        const btn = this.elements.deployBtn;
        if (!btn) return;

        btn.disabled = isLoading;
        if (isLoading) {
            btn.innerHTML = `<span class="spinner"></span> <span>Queuing deployment...</span>`;
        } else {
            btn.innerHTML = `<span>🚀 Deploy Application</span>`;
        }
    },

    /**
     * Toggle specific deployment stop button spinner
     * @param {string} id Deployment UUID
     * @param {boolean} isLoading True if loading
     */
    toggleStopBtnLoading(id, isLoading) {
        const btn = document.getElementById(`stop-${id}`);
        if (!btn) return;

        btn.disabled = isLoading;
        if (isLoading) {
            btn.innerHTML = `<span class="spinner"></span>`;
        } else {
            btn.textContent = '🛑 Stop';
        }
    },

    // --- Helper Utilities ---
    getRepoNameFromUrl(url) {
        if (!url) return 'Unknown App';
        try {
            const parts = url.replace(/\/$/, "").split("/");
            return parts[parts.length - 1] || 'App';
        } catch (e) {
            return 'App';
        }
    },

    formatDate(dateString) {
        if (!dateString) return 'n/a';
        try {
            const date = new Date(dateString);
            return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + ' ' + 
                   date.toLocaleDateString([], { month: 'short', day: 'numeric' });
        } catch (e) {
            return dateString;
        }
    }
};
