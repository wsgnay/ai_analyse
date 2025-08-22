package com.example.ffmpeg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 直播流配置属性类
 */
@Data
@Component
@ConfigurationProperties(prefix = "livestream")
public class LivestreamConfigProperties {

    /**
     * RTMP配置
     */
    private RtmpServerConfig rtmp = new RtmpServerConfig();

    /**
     * HLS配置
     */
    private HlsConfig hls = new HlsConfig();

    /**
     * 认证配置
     */
    private AuthConfig auth = new AuthConfig();

    @Data
    public static class RtmpServerConfig {
        /**
         * 主服务器配置
         */
        private ServerConfig primary = new ServerConfig();

        /**
         * 备用服务器配置
         */
        private List<ServerConfig> backup;
    }

    @Data
    public static class ServerConfig {
        /**
         * 服务器主机地址
         */
        private String host = "47.122.22.70";

        /**
         * 服务器端口
         */
        private int port = 1935;

        /**
         * 应用名称
         */
        private String app = "live";

        /**
         * 构建完整的RTMP URL
         */
        public String buildRtmpUrl() {
            return String.format("rtmp://%s:%d/%s/", host, port, app);
        }

        /**
         * 构建带流名称的完整URL
         */
        public String buildRtmpUrl(String streamName) {
            return String.format("rtmp://%s:%d/%s/%s", host, port, app, streamName);
        }
    }

    @Data
    public static class HlsConfig {
        /**
         * 是否启用HLS
         */
        private boolean enabled = false;

        /**
         * HLS基础URL
         */
        private String baseUrl = "http://47.122.22.70:8080/hls/";

        /**
         * 切片时长(秒)
         */
        private int segmentDuration = 6;

        /**
         * 切片数量
         */
        private int segmentCount = 3;
    }

    @Data
    public static class AuthConfig {
        /**
         * 是否启用认证
         */
        private boolean enabled = false;

        /**
         * 推流密钥前缀
         */
        private String streamKeyPrefix = "ai_stream_";

        /**
         * 密钥过期时间(小时)
         */
        private int keyExpiryHours = 24;
    }
}
