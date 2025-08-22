package com.example.ffmpeg.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamConnectionTest {

    /**
     * 连接是否成功
     */
    private Boolean connected;

    /**
     * 测试的RTMP地址
     */
    private String rtmpUrl;

    /**
     * 服务器主机
     */
    private String serverHost;

    /**
     * 响应时间(毫秒)
     */
    private Integer responseTimeMs;

    /**
     * 测试结果消息
     */
    private String message;

    /**
     * 测试时间
     */
    private LocalDateTime testTime;

    /**
     * 推荐建议
     */
    private List<String> recommendations;

    /**
     * 错误详情 (连接失败时)
     */
    private String errorDetails;

    /**
     * 网络质量评估
     */
    private NetworkQuality networkQuality;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetworkQuality {
        /**
         * 质量等级 (excellent, good, fair, poor)
         */
        private String level;

        /**
         * 建议的比特率
         */
        private Integer recommendedBitrate;

        /**
         * 延迟评估
         */
        private String latencyAssessment;

        /**
         * 带宽估算
         */
        private String bandwidthEstimate;
    }
}
