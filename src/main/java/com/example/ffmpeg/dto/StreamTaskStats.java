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
public class StreamTaskStats {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 统计开始时间
     */
    private LocalDateTime startTime;

    /**
     * 最后更新时间
     */
    private LocalDateTime lastUpdateTime;

    /**
     * 视频统计
     */
    private VideoStats videoStats;

    /**
     * AI检测统计
     */
    private DetectionStats detectionStats;

    /**
     * 推流统计
     */
    private StreamingStats streamingStats;

    /**
     * 性能统计
     */
    private PerformanceStats performanceStats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VideoStats {
        private Integer totalFrames;
        private Integer processedFrames;
        private Double currentFps;
        private Double averageFps;
        private Integer width;
        private Integer height;
        private String codec;
        private Long durationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectionStats {
        private Integer totalDetections;
        private Integer totalPersonsFound;
        private Integer apiCallsCount;
        private Double averageConfidence;
        private Double averageDetectionTime;
        private List<Integer> detectionsPerFrame;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamingStats {
        private Long totalBytesStreamed;
        private Double currentBitrate;
        private Double averageBitrate;
        private Integer droppedFrames;
        private Integer retransmissions;
        private String connectionStatus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceStats {
        private Double cpuUsage;
        private Double memoryUsage;
        private Double networkUsage;
        private Integer activeThreads;
        private Long heapMemoryUsed;
        private Long nonHeapMemoryUsed;
    }
}
