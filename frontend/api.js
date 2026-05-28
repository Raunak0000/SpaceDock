/**
 * SpaceDock API Service
 * Encapsulates all backend HTTP fetch requests and STOMP WebSocket log streaming logic.
 */

const BACKEND_URL = window.location.port === '8082' ? window.location.origin : 'http://localhost:8082';

let stompClient = null;
let activeSubscription = null;

export const ApiService = {
    /**
     * Retrieve local storage X-API-Key
     */
    getApiKey() {
        return localStorage.getItem('spacedock_api_key') || '';
    },

    /**
     * Cache X-API-Key to local storage
     */
    saveApiKey(key) {
        localStorage.setItem('spacedock_api_key', key.trim());
    },

    /**
     * Get base headers including api key if set
     */
    _getHeaders() {
        const headers = { 'Content-Type': 'application/json' };
        const key = this.getApiKey();
        if (key) {
            headers['X-API-Key'] = key;
        }
        return headers;
    },

    /**
     * Fetch all active deployments
     */
    async fetchDeployments() {
        const res = await fetch(`${BACKEND_URL}/api/deployments`, {
            headers: this._getHeaders()
        });
        if (res.status === 401 || res.status === 403) {
            throw new Error("Unauthorized (check your X-API-Key)");
        }
        if (!res.ok) {
            throw new Error(`Failed to load deployments (HTTP ${res.status})`);
        }
        return res.json();
    },

    /**
     * Trigger a new deployment
     * @param {string} repoUrl GitHub HTTPS repository URL
     * @param {Object} envVars Key-value pairs of environment variables
     */
    async deploy(repoUrl, envVars) {
        const res = await fetch(`${BACKEND_URL}/api/deployments`, {
            method: 'POST',
            headers: this._getHeaders(),
            body: JSON.stringify({ repoUrl, envVars })
        });
        const text = await res.text();
        if (!res.ok) {
            throw new Error(text || `Failed to trigger deployment (HTTP ${res.status})`);
        }
        // Response format: "Deployment queued. ID: <UUID>"
        const parts = text.split("ID: ");
        if (parts.length < 2) {
            throw new Error("Invalid server response format. Missing Deployment ID.");
        }
        return parts[1].trim(); // returns deploymentId UUID
    },

    /**
     * Stop a running deployment container
     * @param {string} id Deployment UUID
     */
    async stopDeployment(id) {
        const res = await fetch(`${BACKEND_URL}/api/deployments/${id}`, {
            method: 'DELETE',
            headers: this._getHeaders()
        });
        if (!res.ok) {
            const text = await res.text();
            throw new Error(text || `Failed to stop container (HTTP ${res.status})`);
        }
        return res.text();
    },

    /**
     * Establish the STOMP/SockJS connection to the backend
     * @param {Function} onConnect Callback executed on successful connection
     * @param {Function} onDisconnect Callback executed on disconnect/error
     */
    connectWebSocket(onConnect, onDisconnect) {
        // Clear any old instances if reconnecting
        if (stompClient) {
            try { stompClient.disconnect(); } catch (e) {}
        }

        const socket = new SockJS(`${BACKEND_URL}/ws`);
        stompClient = Stomp.over(socket);
        stompClient.debug = null; // Mute heavy STOMP frame print noise in client logs

        stompClient.connect({}, 
            () => {
                if (onConnect) onConnect();
            }, 
            (error) => {
                if (onDisconnect) onDisconnect(error);
            }
        );
    },

    /**
     * Check if WebSocket is currently open
     */
    isWebSocketConnected() {
        return stompClient && stompClient.connected;
    },

    /**
     * Subscribe to real-time build & run logs for a deployment
     * @param {string} deploymentId Deployment UUID
     * @param {Function} onLogLine Callback receiving (text, type) for each line
     */
    subscribeToLogs(deploymentId, onLogLine) {
        this.unsubscribeFromLogs();

        if (!this.isWebSocketConnected()) {
            onLogLine('❌ WebSocket client offline. Unable to stream logs.', 'error');
            return;
        }

        const topic = `/topic/logs/${deploymentId}`;
        activeSubscription = stompClient.subscribe(topic, (message) => {
            const line = message.body;
            let type = '';

            if (line.startsWith('❌') || line.toLowerCase().includes('error') || line.toLowerCase().includes('failed')) {
                type = 'error';
            } else if (line.startsWith('✅') || line.startsWith('🌍') || line.toLowerCase().includes('success')) {
                type = 'success';
            } else if (line.startsWith('📡') || line.startsWith('🐳') || line.startsWith('🧹')) {
                type = 'info';
            }

            onLogLine(line, type);
        });
    },

    /**
     * Unsubscribe from the current log topic
     */
    unsubscribeFromLogs() {
        if (activeSubscription) {
            activeSubscription.unsubscribe();
            activeSubscription = null;
        }
    }
};
