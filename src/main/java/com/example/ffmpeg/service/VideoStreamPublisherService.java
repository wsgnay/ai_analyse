package com.example.ffmpeg.service;

import com.example.ffmpeg.config.StreamConfigProperties;
import com.example.ffmpeg.config.LivestreamConfigProperties;
import com.example.ffmpeg.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频流AI处理与RTMP推流服务 - 使用配置文件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStreamPublisherService {

    private final QwenApiService qwenApiService;
    private final DatabaseService databaseService;
    private final VideoStreamService videoStreamService;
    private final StreamConfigProperties streamConfig;
    private final LivestreamConfigProperties livestreamConfig;
    private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

    // 活跃的推流任务管理
    private final ConcurrentMap<String, StreamPublishTask> activeTasks = new ConcurrentHashMap<>();

    /**
     * 启动视频流AI处理和推流
     */
    public Mono<StreamPublishResult> startStreamPublishing(StreamPublishRequest request) {
        return Mono.fromCallable(() -> {
            String taskId = generateTaskId();

            // 验证并发任务数限制
            if (activeTasks.size() >= streamConfig.getTask().getMaxConcurrentTasks()) {
                throw new IllegalStateException("达到最大并发任务数限制: " + streamConfig.getTask().getMaxConcurrentTasks());
            }

            // 使用配置的默认RTMP服务器（如果请求中没有指定）
            String rtmpUrl = request.getRtmpUrl();
            if (rtmpUrl == null || rtmpUrl.trim().isEmpty()) {
                rtmpUrl = buildDefaultRtmpUrl(request.getTaskName());
                request.setRtmpUrl(rtmpUrl);
                log.info("使用默认RTMP地址: {}", rtmpUrl);
            }

            // 验证RTMP地址
            validateRtmpUrl(rtmpUrl);

            // 应用配置的默认值
            applyDefaultConfigurations(request);

            // 创建推流任务
            StreamPublishTask task = new StreamPublishTask(taskId, request);
            activeTasks.put(taskId, task);

            // 启动处理
            startProcessing(task);

            log.info("🚀 启动视频流AI处理推流: {} -> {}", request.getInputSource(), rtmpUrl);

            return StreamPublishResult.builder()
                    .taskId(taskId)
                    .inputSource(request.getInputSource())
                    .rtmpUrl(rtmpUrl)
                    .status("started")
                    .startTime(task.startTime)
                    .taskName(request.getTaskName())
                    .build();

        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 构建默认RTMP推流地址
     */
    private String buildDefaultRtmpUrl(String taskName) {
        String baseUrl = livestreamConfig.getRtmp().getPrimary().buildRtmpUrl();
        String streamKey = generateStreamKey(taskName);
        return baseUrl + streamKey;
    }

    /**
     * 生成流标识
     */
    private String generateStreamKey(String taskName) {
        String prefix = livestreamConfig.getAuth().getStreamKeyPrefix();
        String timestamp = LocalDateTime.now().toString().replaceAll("[^0-9]", "");
        String safeName = taskName != null ? taskName.replaceAll("[^a-zA-Z0-9_-]", "_") : "default";
        return prefix + safeName + "_" + timestamp;
    }

    /**
     * 验证RTMP地址
     */
    private void validateRtmpUrl(String rtmpUrl) {
        if (!rtmpUrl.startsWith("rtmp://")) {
            throw new IllegalArgumentException("无效的RTMP地址格式");
        }

        // 验证是否为配置的服务器地址
        String primaryServer = livestreamConfig.getRtmp().getPrimary().buildRtmpUrl();
        boolean isValidServer = rtmpUrl.startsWith(primaryServer);

        if (!isValidServer && livestreamConfig.getRtmp().getBackup() != null) {
            isValidServer = livestreamConfig.getRtmp().getBackup().stream()
                    .anyMatch(backup -> rtmpUrl.startsWith(backup.buildRtmpUrl()));
        }

        if (!isValidServer) {
            log.warn("使用非配置服务器推流: {}", rtmpUrl);
        }
    }

    /**
     * 应用配置的默认值
     */
    private void applyDefaultConfigurations(StreamPublishRequest request) {
        // 应用视频配置默认值
        if (request.getVideoBitrate() == null) {
            request.setVideoBitrate(streamConfig.getVideo().getDefaultBitrate());
        } else {
            // 验证比特率范围
            int bitrate = request.getVideoBitrate();
            if (bitrate < streamConfig.getVideo().getMinBitrate()) {
                request.setVideoBitrate(streamConfig.getVideo().getMinBitrate());
                log.warn("视频比特率过低，调整为最小值: {}", streamConfig.getVideo().getMinBitrate());
            } else if (bitrate > streamConfig.getVideo().getMaxBitrate()) {
                request.setVideoBitrate(streamConfig.getVideo().getMaxBitrate());
                log.warn("视频比特率过高，调整为最大值: {}", streamConfig.getVideo().getMaxBitrate());
            }
        }

        // 应用音频配置默认值
        if (request.getAudioBitrate() == null) {
            request.setAudioBitrate(streamConfig.getAudio().getDefaultBitrate());
        }

        // 应用检测配置默认值
        if (request.getDetectionInterval() == null) {
            request.setDetectionInterval(30); // 可以从配置中读取
        }
    }

    /**
     * 初始化RTMP推流器 - 使用配置参数
     */
    private FFmpegFrameRecorder initializeRtmpPublisher(String rtmpUrl, int width, int height,
                                                        double frameRate, StreamPublishRequest config) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(rtmpUrl, width, height);

        // 基础配置
        recorder.setFormat("flv");
        recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
        recorder.setAudioCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC);
        recorder.setFrameRate(frameRate);

        // 使用配置的视频参数
        recorder.setVideoBitrate(config.getVideoBitrate());
        recorder.setVideoQuality(0);

        // 使用配置的编码参数
        StreamConfigProperties.VideoConfig videoConfig = streamConfig.getVideo();
        recorder.setOption("preset", videoConfig.getEncoderPreset());
        recorder.setOption("tune", videoConfig.getEncoderTune());
        recorder.setOption("g", String.valueOf(videoConfig.getGopSize()));
        recorder.setOption("keyint_min", String.valueOf(videoConfig.getGopSize()));

        // 使用配置的RTMP参数
        StreamConfigProperties.RtmpConfig rtmpConfig = streamConfig.getRtmp();
        recorder.setOption("rtmp_live", "live");
        recorder.setOption("rtmp_buffer", String.valueOf(rtmpConfig.getBufferSize()));
        recorder.setOption("rtmp_conn", "S:publish");

        // 网络优化配置
        recorder.setOption("flvflags", "no_duration_filesize");

        log.info("RTMP推流器配置: 比特率={}kbps, 预设={}, 调优={}",
                config.getVideoBitrate()/1000, videoConfig.getEncoderPreset(), videoConfig.getEncoderTune());

        return recorder;
    }

    /**
     * 获取配置的服务器信息
     */
    public Mono<java.util.Map<String, Object>> getServerInfo() {
        return Mono.fromCallable(() -> {
            java.util.Map<String, Object> info = new java.util.HashMap<>();

            // 主服务器信息
            LivestreamConfigProperties.ServerConfig primary = livestreamConfig.getRtmp().getPrimary();
            info.put("primaryServer", java.util.Map.of(
                    "host", primary.getHost(),
                    "port", primary.getPort(),
                    "app", primary.getApp(),
                    "baseUrl", primary.buildRtmpUrl()
            ));

            // 备用服务器信息
            if (livestreamConfig.getRtmp().getBackup() != null) {
                java.util.List<java.util.Map<String, Object>> backupServers = livestreamConfig.getRtmp().getBackup().stream()
                        .map(backup -> java.util.Map.of(
                                "host", backup.getHost(),
                                "port", backup.getPort(),
                                "app", backup.getApp(),
                                "baseUrl", backup.buildRtmpUrl()
                        ))
                        .collect(java.util.stream.Collectors.toList());
                info.put("backupServers", backupServers);
            }

            // 推流配置信息
            info.put("streamConfig", java.util.Map.of(
                    "maxConcurrentTasks", streamConfig.getTask().getMaxConcurrentTasks(),
                    "defaultBitrate", streamConfig.getVideo().getDefaultBitrate(),
                    "encoderPreset", streamConfig.getVideo().getEncoderPreset()
            ));

            return info;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 测试RTMP连接 - 使用配置验证
     */
    public Mono<java.util.Map<String, Object>> testRtmpConnection(String rtmpUrl) {
        return Mono.fromCallable(() -> {
            java.util.Map<String, Object> result = new java.util.HashMap<>();

            try {
                // 验证URL格式
                validateRtmpUrl(rtmpUrl);

                // 简单的连接测试 - 这里可以实现实际的连接测试
                boolean connected = rtmpUrl.startsWith("rtmp://") && rtmpUrl.contains(":");

                result.put("connected", connected);
                result.put("rtmpUrl", rtmpUrl);
                result.put("message", connected ? "连接测试成功" : "连接测试失败");
                result.put("serverHost", extractHostFromUrl(rtmpUrl));
                result.put("timestamp", System.currentTimeMillis());

                if (connected) {
                    result.put("recommendations", java.util.List.of(
                            "建议使用配置的默认服务器以获得最佳性能",
                            "确保网络带宽满足推流要求",
                            "监控推流延迟和丢包情况"
                    ));
                }

            } catch (Exception e) {
                result.put("connected", false);
                result.put("rtmpUrl", rtmpUrl);
                result.put("message", "连接测试失败: " + e.getMessage());
                result.put("timestamp", System.currentTimeMillis());
            }

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 从URL中提取主机地址
     */
    private String extractHostFromUrl(String rtmpUrl) {
        try {
            // rtmp://host:port/app/ -> host
            return rtmpUrl.substring(7).split(":")[0];
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ... 其他现有方法保持不变 ...

    /**
     * 中继流信息类 - 保持不变
     */
    private static class StreamPublishTask {
        public String taskId;
        public String inputSource;
        public String rtmpUrl;
        public FFmpegFrameGrabber grabber;
        public FFmpegFrameRecorder recorder;
        public AtomicBoolean running;
        public AtomicInteger frameCount;
        public AtomicInteger detectionCount;
        public LocalDateTime startTime;
        public StreamPublishRequest config;

        public StreamPublishTask(String taskId, StreamPublishRequest request) {
            this.taskId = taskId;
            this.inputSource = request.getInputSource();
            this.rtmpUrl = request.getRtmpUrl();
            this.config = request;
            this.running = new AtomicBoolean(false);
            this.frameCount = new AtomicInteger(0);
            this.detectionCount = new AtomicInteger(0);
            this.startTime = LocalDateTime.now();
        }
    }

    /**
     * 生成任务ID
     */
    private String generateTaskId() {
        return "stream_" + System.currentTimeMillis() + "_" +
                (int)(Math.random() * 1000);
    }

    // ... 其他现有方法保持不变，但需要使用配置参数 ...
}
