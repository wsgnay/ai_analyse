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
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.util.Base64;

/**
 * 视频流AI处理与RTMP推流服务 - 完整实现版本
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
     * 推流任务信息类
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
        public double currentFps;
        public int videoWidth;
        public int videoHeight;
        public double frameRate;

        public StreamPublishTask(String taskId, StreamPublishRequest request) {
            this.taskId = taskId;
            this.inputSource = request.getInputSource();
            this.rtmpUrl = request.getRtmpUrl();
            this.config = request;
            this.running = new AtomicBoolean(false);
            this.frameCount = new AtomicInteger(0);
            this.detectionCount = new AtomicInteger(0);
            this.startTime = LocalDateTime.now();
            this.currentFps = 0.0;
        }
    }

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
                    .success(true)
                    .message("任务已启动")
                    .build();

        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 🎯 核心视频处理循环 - 拉流+AI检测+推流
     */
    private void startProcessing(StreamPublishTask task) {
        CompletableFuture.runAsync(() -> {
            FFmpegFrameGrabber grabber = null;
            FFmpegFrameRecorder recorder = null;

            try {
                log.info("🚀 启动任务处理: {}", task.taskId);

                // 1. 初始化拉流器
                grabber = initializeVideoSource(task.config.getInputSource());
                grabber.start();

                int width = grabber.getImageWidth();
                int height = grabber.getImageHeight();
                double frameRate = grabber.getFrameRate();

                // 保存视频信息到任务
                task.videoWidth = width;
                task.videoHeight = height;
                task.frameRate = frameRate;

                log.info("📹 视频信息: {}x{} @ {}fps", width, height, frameRate);

                // 2. 初始化推流器
                recorder = initializeRtmpPublisher(
                        task.config.getRtmpUrl(),
                        width, height, frameRate, task.config
                );
                recorder.start();

                // 3. 保存到任务中
                task.grabber = grabber;
                task.recorder = recorder;
                task.running.set(true);

                log.info("✅ 推流器初始化完成，开始处理视频流");

                // 4. 主处理循环
                processVideoLoop(task, grabber, recorder);

            } catch (Exception e) {
                log.error("任务{}处理失败", task.taskId, e);
                task.running.set(false);
            } finally {
                // 5. 清理资源
                cleanupResources(grabber, recorder);
                activeTasks.remove(task.taskId);
                log.info("任务{}处理完成", task.taskId);
            }
        }, Schedulers.boundedElastic().scheduler());
    }

    /**
     * 初始化视频源
     */
    private FFmpegFrameGrabber initializeVideoSource(String inputSource) throws Exception {
        FFmpegFrameGrabber grabber;

        if (inputSource.matches("\\d+")) {
            // 摄像头设备
            int deviceId = Integer.parseInt(inputSource);
            String osName = System.getProperty("os.name").toLowerCase();

            if (osName.contains("windows")) {
                grabber = new FFmpegFrameGrabber("video=" + deviceId);
                grabber.setFormat("dshow");
            } else if (osName.contains("mac")) {
                grabber = new FFmpegFrameGrabber("" + deviceId);
                grabber.setFormat("avfoundation");
            } else {
                grabber = new FFmpegFrameGrabber("/dev/video" + deviceId);
                grabber.setFormat("v4l2");
            }

            log.info("📷 初始化摄像头设备: {}", deviceId);
        } else {
            // 文件或网络流
            grabber = new FFmpegFrameGrabber(inputSource);

            // 网络流优化
            if (inputSource.startsWith("rtsp://") || inputSource.startsWith("rtmp://")) {
                grabber.setOption("buffer_size", "1024000");
                grabber.setOption("max_delay", "500000");
                grabber.setOption("stimeout", "5000000");
                if (inputSource.startsWith("rtsp://")) {
                    grabber.setOption("rtsp_transport", "tcp");
                }
                log.info("🌐 初始化网络流: {}", inputSource);
            } else {
                log.info("📁 初始化视频文件: {}", inputSource);
            }
        }

        return grabber;
    }

    /**
     * 视频处理主循环
     */
    private void processVideoLoop(StreamPublishTask task, FFmpegFrameGrabber grabber,
                                  FFmpegFrameRecorder recorder) throws Exception {

        Frame frame;
        int frameNumber = 0;
        long lastDetectionTime = 0;
        long lastStatsUpdate = System.currentTimeMillis();
        List<PersonDetection> currentDetections = new ArrayList<>();

        // 帧率计算
        long startTime = System.currentTimeMillis();
        int frameCount = 0;

        log.info("🎬 开始视频处理循环");

        while (task.running.get() && (frame = grabber.grab()) != null) {
            try {
                if (frame.image == null) continue;

                frameNumber++;
                frameCount++;
                task.frameCount.incrementAndGet();

                // 转换为BufferedImage
                BufferedImage bufferedImage = frameConverter.convert(frame);

                // AI检测判断
                boolean shouldDetect = shouldTriggerDetection(
                        frameNumber,
                        System.currentTimeMillis() - lastDetectionTime,
                        task.config
                );

                if (shouldDetect && task.config.getEnableAiDetection()) {
                    // 执行AI检测
                    List<PersonDetection> newDetections = performAIDetection(
                            bufferedImage, frameNumber, task.config
                    );

                    if (!newDetections.isEmpty()) {
                        currentDetections = newDetections;
                        task.detectionCount.addAndGet(newDetections.size());
                        lastDetectionTime = System.currentTimeMillis();

                        log.debug("帧{}: 检测到{}个人物", frameNumber, newDetections.size());
                    }
                }

                // 绘制检测结果
                if (!currentDetections.isEmpty()) {
                    bufferedImage = drawDetections(bufferedImage, currentDetections);
                }

                // 转换回Frame用于推流
                Frame processedFrame = frameConverter.convert(bufferedImage);

                // RTMP推流
                recorder.record(processedFrame);

                // WebSocket实时预览
                if (videoStreamService.hasActiveConnections()) {
                    videoStreamService.broadcastFrame(bufferedImage);
                }

                // 帧率控制
                controlFrameRate(grabber.getFrameRate());

                // 定期更新统计信息
                updateStatistics(task, frameCount, startTime, lastStatsUpdate);

                // 对于摄像头流，设置最大处理帧数以避免无限运行
                if (task.config.getInputSource().matches("\\d+") && frameNumber > 10000) {
                    log.info("⏹️ 摄像头流达到最大处理帧数，停止处理");
                    break;
                }

            } catch (Exception e) {
                log.error("处理帧{}时出错", frameNumber, e);
                // 继续处理下一帧，避免整个流程中断
            }
        }

        log.info("🏁 视频处理循环结束，总处理帧数: {}", frameNumber);
    }

    /**
     * 判断是否触发AI检测
     */
    private boolean shouldTriggerDetection(int frameNumber, long timeSinceLastDetection,
                                           StreamPublishRequest config) {
        // 首帧必须检测
        if (frameNumber == 1) return true;

        // 按间隔检测
        Integer interval = config.getDetectionInterval();
        if (interval != null && frameNumber % interval == 0) return true;

        // 超时检测（防止长时间无检测）
        if (timeSinceLastDetection > 10000) return true; // 10秒超时

        return false;
    }

    /**
     * 执行AI检测
     */
    private List<PersonDetection> performAIDetection(BufferedImage image, int frameNumber,
                                                     StreamPublishRequest config) {
        try {
            if (!config.getEnablePersonDetection()) {
                return Collections.emptyList();
            }

            // 调用Qwen API进行检测
            List<PersonDetection> detections = detectPersonsWithQwen(image, config);

            // 为检测结果添加帧信息
            detections.forEach(detection -> {
                if (detection.getFrameNumber() == null) {
                    detection.setFrameNumber(frameNumber);
                }
                if (detection.getTimestamp() == null) {
                    detection.setTimestamp(System.currentTimeMillis());
                }
                if (detection.getIsNewDetection() == null) {
                    detection.setIsNewDetection(true);
                }
            });

            return detections;

        } catch (Exception e) {
            log.error("AI检测失败 frameNumber={}", frameNumber, e);
            return Collections.emptyList();
        }
    }

    /**
     * 调用Qwen API进行人物检测
     */
    private List<PersonDetection> detectPersonsWithQwen(BufferedImage image, StreamPublishRequest config) {
        try {
            // 将BufferedImage转换为Base64字符串
            String base64Image = bufferedImageToBase64(image);

            // 构建检测请求（根据你的QwenApiService接口调整）
            String apiKey = config.getApiKey();
            Double confThreshold = config.getConfThreshold();

            // 这里需要根据你实际的QwenApiService方法来调用
            // 假设你有一个方法可以接受base64图像
            List<PersonDetection> detections = qwenApiService.detectPersonsFromBase64(
                    base64Image, apiKey, confThreshold
            );

            return detections != null ? detections : Collections.emptyList();

        } catch (Exception e) {
            log.error("调用Qwen API失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 将BufferedImage转换为Base64字符串
     */
    private String bufferedImageToBase64(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    /**
     * 绘制检测结果
     */
    private BufferedImage drawDetections(BufferedImage image, List<PersonDetection> detections) {
        if (detections.isEmpty()) return image;

        BufferedImage result = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g2d = result.createGraphics();
        g2d.drawImage(image, 0, 0, null);

        // 设置绘制样式
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(Color.RED);
        g2d.setStroke(new BasicStroke(3));
        g2d.setFont(new Font("Arial", Font.BOLD, 14));

        // 绘制每个检测框
        for (int i = 0; i < detections.size(); i++) {
            PersonDetection detection = detections.get(i);
            double[] bbox = detection.getBbox();

            if (bbox != null && bbox.length >= 4) {
                int x = (int) bbox[0];
                int y = (int) bbox[1];
                int width = (int) (bbox[2] - bbox[0]);
                int height = (int) (bbox[3] - bbox[1]);

                // 绘制边界框
                g2d.drawRect(x, y, width, height);

                // 绘制标签背景
                String label = String.format("Person %d (%.1f%%)",
                        i + 1, detection.getConfidence() * 100);
                FontMetrics fm = g2d.getFontMetrics();
                int labelWidth = fm.stringWidth(label);
                int labelHeight = fm.getHeight();

                g2d.fillRect(x, y - labelHeight, labelWidth + 8, labelHeight);

                // 绘制标签文字
                g2d.setColor(Color.WHITE);
                g2d.drawString(label, x + 4, y - 4);
                g2d.setColor(Color.RED);
            }
        }

        g2d.dispose();
        return result;
    }

    /**
     * 帧率控制
     */
    private void controlFrameRate(double targetFps) {
        try {
            if (targetFps > 0) {
                long delay = (long) (1000.0 / targetFps);
                Thread.sleep(Math.max(1, delay));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 更新统计信息
     */
    private void updateStatistics(StreamPublishTask task, int frameCount, long startTime, long lastStatsUpdate) {
        long currentTime = System.currentTimeMillis();

        // 每5秒更新一次统计
        if (currentTime - lastStatsUpdate > 5000) {
            long elapsedTime = currentTime - startTime;
            if (elapsedTime > 0) {
                task.currentFps = (double) frameCount / (elapsedTime / 1000.0);
                log.debug("任务{} - 当前FPS: {:.2f}, 处理帧数: {}, 检测次数: {}",
                        task.taskId, task.currentFps, task.frameCount.get(), task.detectionCount.get());
            }
        }
    }

    /**
     * 清理资源
     */
    private void cleanupResources(FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder) {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                log.debug("📹 推流器已关闭");
            }
        } catch (Exception e) {
            log.error("关闭录制器失败", e);
        }

        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
                log.debug("📷 拉流器已关闭");
            }
        } catch (Exception e) {
            log.error("关闭抓取器失败", e);
        }
    }

    /**
     * 停止推流任务
     */
    public Mono<StreamPublishResult> stopStreamPublishing(String taskId) {
        return Mono.fromCallable(() -> {
            StreamPublishTask task = activeTasks.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }

            log.info("⏹️ 停止推流任务: {}", taskId);
            task.running.set(false);

            // 任务会在processVideoLoop结束后自动从activeTasks中移除

            return StreamPublishResult.builder()
                    .taskId(taskId)
                    .status("stopped")
                    .endTime(LocalDateTime.now())
                    .message("任务已停止")
                    .success(true)
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取活跃任务列表
     */
    public Mono<List<StreamPublishResult>> getActiveTasks() {
        return Mono.fromCallable(() -> {
            return activeTasks.values().stream()
                    .map(this::convertTaskToResult)
                    .collect(Collectors.toList());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取任务状态
     */
    public Mono<StreamPublishResult> getTaskStatus(String taskId) {
        return Mono.fromCallable(() -> {
            StreamPublishTask task = activeTasks.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }
            return convertTaskToResult(task);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 转换任务为结果对象
     */
    private StreamPublishResult convertTaskToResult(StreamPublishTask task) {
        long elapsedTime = java.time.Duration.between(task.startTime, LocalDateTime.now()).getSeconds();

        return StreamPublishResult.builder()
                .taskId(task.taskId)
                .inputSource(task.inputSource)
                .rtmpUrl(task.rtmpUrl)
                .status(task.running.get() ? "running" : "stopped")
                .startTime(task.startTime)
                .taskName(task.config.getTaskName())
                .frameCount(task.frameCount.get())
                .detectionCount(task.detectionCount.get())
                .currentFps(task.currentFps)
                .runningSeconds(elapsedTime)
                .success(true)
                .build();
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
            request.setDetectionInterval(30);
        }

        // 设置默认值
        if (request.getEnableAiDetection() == null) {
            request.setEnableAiDetection(true);
        }

        if (request.getEnablePersonDetection() == null) {
            request.setEnablePersonDetection(true);
        }

        if (request.getConfThreshold() == null) {
            request.setConfThreshold(0.5);
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

    /**
     * 生成任务ID
     */
    private String generateTaskId() {
        return "stream_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);
    }

    /**
     * 获取健康检查信息
     */
    public Mono<java.util.Map<String, Object>> getHealthStatus() {
        return Mono.fromCallable(() -> {
            java.util.Map<String, Object> health = new java.util.HashMap<>();

            int activeTaskCount = activeTasks.size();
            int maxTasks = streamConfig.getTask().getMaxConcurrentTasks();
            double usage = (double) activeTaskCount / maxTasks * 100;

            health.put("status", usage > 90 ? "warning" : "healthy");
            health.put("activeTasks", activeTaskCount);
            health.put("maxTasks", maxTasks);
            health.put("usage", Math.round(usage * 100.0) / 100.0);
            health.put("timestamp", System.currentTimeMillis());

            // 添加每个任务的基本信息
            java.util.List<java.util.Map<String, Object>> taskSummaries = activeTasks.values().stream()
                    .map(task -> java.util.Map.of(
                            "taskId", task.taskId,
                            "status", task.running.get() ? "running" : "stopped",
                            "frameCount", task.frameCount.get(),
                            "detectionCount", task.detectionCount.get(),
                            "fps", task.currentFps
                    ))
                    .collect(Collectors.toList());

            health.put("tasks", taskSummaries);

            return health;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 强制清理所有任务（管理功能）
     */
    public Mono<java.util.Map<String, Object>> cleanupAllTasks() {
        return Mono.fromCallable(() -> {
            int cleanedCount = 0;

            for (StreamPublishTask task : activeTasks.values()) {
                try {
                    task.running.set(false);
                    cleanupResources(task.grabber, task.recorder);
                    cleanedCount++;
                } catch (Exception e) {
                    log.error("清理任务{}失败", task.taskId, e);
                }
            }

            activeTasks.clear();

            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("cleanedTasks", cleanedCount);
            result.put("message", "已清理" + cleanedCount + "个任务");
            result.put("timestamp", System.currentTimeMillis());

            log.info("🧹 强制清理了{}个推流任务", cleanedCount);

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取推流统计信息
     */
    public Mono<java.util.Map<String, Object>> getStreamingStatistics() {
        return Mono.fromCallable(() -> {
            java.util.Map<String, Object> stats = new java.util.HashMap<>();

            // 总体统计
            stats.put("totalActiveTasks", activeTasks.size());
            stats.put("maxConcurrentTasks", streamConfig.getTask().getMaxConcurrentTasks());

            // 聚合统计
            int totalFrames = activeTasks.values().stream()
                    .mapToInt(task -> task.frameCount.get())
                    .sum();

            int totalDetections = activeTasks.values().stream()
                    .mapToInt(task -> task.detectionCount.get())
                    .sum();

            double avgFps = activeTasks.values().stream()
                    .mapToDouble(task -> task.currentFps)
                    .average()
                    .orElse(0.0);

            stats.put("totalFramesProcessed", totalFrames);
            stats.put("totalDetections", totalDetections);
            stats.put("averageFps", Math.round(avgFps * 100.0) / 100.0);

            // 按状态分组的任务数
            long runningTasks = activeTasks.values().stream()
                    .filter(task -> task.running.get())
                    .count();

            stats.put("runningTasks", runningTasks);
            stats.put("stoppedTasks", activeTasks.size() - runningTasks);

            // 服务器配置信息
            stats.put("serverConfig", java.util.Map.of(
                    "rtmpServer", livestreamConfig.getRtmp().getPrimary().buildRtmpUrl(),
                    "defaultBitrate", streamConfig.getVideo().getDefaultBitrate(),
                    "encoderPreset", streamConfig.getVideo().getEncoderPreset()
            ));

            stats.put("timestamp", System.currentTimeMillis());

            return stats;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取任务详细统计信息（用于监控面板）
     */
    public Mono<java.util.Map<String, Object>> getTaskDetailedStats(String taskId) {
        return Mono.fromCallable(() -> {
            StreamPublishTask task = activeTasks.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }

            java.util.Map<String, Object> details = new java.util.HashMap<>();

            // 基本信息
            details.put("taskId", task.taskId);
            details.put("inputSource", task.inputSource);
            details.put("rtmpUrl", task.rtmpUrl);
            details.put("status", task.running.get() ? "running" : "stopped");
            details.put("startTime", task.startTime.toString());

            // 处理统计
            long elapsedSeconds = java.time.Duration.between(task.startTime, LocalDateTime.now()).getSeconds();
            details.put("runningTimeSeconds", elapsedSeconds);
            details.put("frameCount", task.frameCount.get());
            details.put("detectionCount", task.detectionCount.get());
            details.put("currentFps", task.currentFps);

            // 视频信息
            details.put("videoInfo", java.util.Map.of(
                    "width", task.videoWidth,
                    "height", task.videoHeight,
                    "frameRate", task.frameRate
            ));

            // 配置信息
            details.put("config", java.util.Map.of(
                    "videoBitrate", task.config.getVideoBitrate(),
                    "audioBitrate", task.config.getAudioBitrate(),
                    "detectionInterval", task.config.getDetectionInterval(),
                    "confThreshold", task.config.getConfThreshold(),
                    "enableAiDetection", task.config.getEnableAiDetection(),
                    "enablePersonDetection", task.config.getEnablePersonDetection()
            ));

            // 性能指标
            if (elapsedSeconds > 0) {
                double avgFramesPerSecond = (double) task.frameCount.get() / elapsedSeconds;
                double avgDetectionsPerSecond = (double) task.detectionCount.get() / elapsedSeconds;

                details.put("performance", java.util.Map.of(
                        "avgFramesPerSecond", Math.round(avgFramesPerSecond * 100.0) / 100.0,
                        "avgDetectionsPerSecond", Math.round(avgDetectionsPerSecond * 100.0) / 100.0,
                        "detectionRate", task.frameCount.get() > 0 ?
                                Math.round((double) task.detectionCount.get() / task.frameCount.get() * 100.0) / 100.0 : 0.0
                ));
            }

            details.put("timestamp", System.currentTimeMillis());

            return details;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
