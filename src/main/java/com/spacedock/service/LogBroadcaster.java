package com.spacedock.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class LogBroadcaster {
    private final SimpMessagingTemplate messagingTemplate;

    public LogBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastLog(String deploymentId, String logLine) {
        // Send log to all subscribers listening to this deployment's log topic
        String topic = "/topic/deployment/" + deploymentId;
        messagingTemplate.convertAndSend(topic, logLine);
    }
}
