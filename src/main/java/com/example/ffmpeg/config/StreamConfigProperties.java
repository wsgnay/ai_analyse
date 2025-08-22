package com.example.ffmpeg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 推流配置属性类
 */
@Data
@Component
@ConfigurationProperties(prefix = "drone.inspection.streaming")
public class StreamConfigProperties {

    /**
     * RTMP配置
     */
    private RtmpConfig rtmp = new RtmpConfig();

    /**
     * 视频配置
     */
    private VideoConfig video = new VideoConfig();

    /**
     * 音频配置
     */
    private AudioConfig audio = new AudioConfig();

    /**
     * 任务配置
     */
    private TaskConfig task = new TaskConfig();

    @Data
    public static class RtmpConfig {
        /**
         * 默认服务器地址
         */
        private String defaultServer = "rtmp://47.122.22.70:1935/live/";

        /**
         * 备用服务器地址列表
         */
        private List<String> backupServers;

        /**
         * 推流超时时间(秒)
         */
        private int timeout = 30;

        /**
         * 推流重试次数
         */
        private int maxRetries = 3;

        /**
         * 推流缓冲大小
         */
        private int bufferSize = 1000;
    }

    @Data
    public static class VideoConfig {
        /**
         * 默认比特率(bps)
         */
        private int defaultBitrate = 2000000; // 2Mbps

        /**
         * 最小比特率(bps)
         */
        private int minBitrate = 500000; // 500kbps

        /**
         * 最大比特率(bps)
         */
        private int maxBitrate = 10000000; // 10Mbps

        /**
         * 默认帧率
         */
        private int defaultFramerate = 25;

        /**
         * 编码器预设
         */
        private String encoderPreset = "veryfast";

        /**
         * 编码器调优
         */
        private String encoderTune = "zerolatency";

        /**
         * GOP大小
         */
        private int gopSize = 60;
    }

    @Data
    public static class AudioConfig {
        /**
         * 默认音频比特率(bps)
         */
        private int defaultBitrate = 128000; // 128kbps

        /**
         * 采样率
         */
        private int sampleRate = 44100;

        /**
         * 声道数
         */
        private int channels = 2;
    }

    @Data
    public static class TaskConfig {
        /**
         * 最大并发推流任务数
         */
        private int maxConcurrentTasks = 10;

        /**
         * 任务超时时间(分钟)
         */
        private int taskTimeout = 60;

        /**
         * 自动清理失效任务的间隔(分钟)
         */
        private int cleanupInterval = 5;
    }
}
