package com.example.ffmpeg.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamServerInfo {

    /**
     * 主服务器信息
     */
    private ServerConfig primaryServer;

    /**
     * 备用服务器列表
     */
    private List<ServerConfig> backupServers;

    /**
     * 推流配置信息
     */
    private StreamConfigInfo streamConfig;

    /**
     * 服务器健康状态
     */
    private ServerHealth health;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerConfig {
        private String host;
        private Integer port;
        private String app;
        private String baseUrl;
        private Boolean available;
        private Integer latencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamConfigInfo {
        private Integer maxConcurrentTasks;
        private Integer defaultBitrate;
        private String encoderPreset;
        private String encoderTune;
        private Integer gopSize;
        private List<String> supportedCodecs;
        private Map<String, Object> advancedSettings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerHealth {
        private String status; // healthy, warning, error
        private Double cpuUsage;
        private Double memoryUsage;
        private Double diskUsage;
        private Integer activeConnections;
        private String lastCheckTime;
    }
}
