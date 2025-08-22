package com.example.ffmpeg.dto;

import lombok.Data;

/**
 * 视频流发布请求
 */
@Data
public class StreamPublishRequest {
    /**
     * 输入视频源 (摄像头ID/RTSP地址/视频文件路径)
     */
    private String inputSource;

    /**
     * RTMP推流地址
     */
    private String rtmpUrl;

    /**
     * 是否启用AI检测
     */
    private Boolean enableAiDetection = true;

    /**
     * 是否启用人物检测
     */
    private Boolean enablePersonDetection = true;

    /**
     * 检测间隔(帧数)
     */
    private Integer detectionInterval = 30;

    /**
     * 置信度阈值
     */
    private Double confThreshold = 0.5;

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 视频比特率 (bps)
     */
    private Integer videoBitrate = 2000000; // 2Mbps

    /**
     * 音频比特率 (bps)
     */
    private Integer audioBitrate = 128000; // 128kbps

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 任务描述
     */
    private String description;
}
