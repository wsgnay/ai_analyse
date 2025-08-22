package com.example.ffmpeg.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 视频流发布结果
 */
@Data
@Builder
public class StreamPublishResult {
    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 输入视频源
     */
    private String inputSource;

    /**
     * RTMP推流地址
     */
    private String rtmpUrl;

    /**
     * 任务状态 (started/running/stopped/error)
     */
    private String status;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 处理帧数
     */
    private Integer frameCount;

    /**
     * 检测次数
     */
    private Integer detectionCount;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 当前FPS
     */
    private Double currentFps;

    /**
     * 运行时长(秒)
     */
    private Long runningSeconds;

    /**
     * 成功状态
     */
    private Boolean success;

    /**
     * 消息
     */
    private String message;

    /**
     * 创建时间
     */
    private LocalDateTime createdTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;
}
