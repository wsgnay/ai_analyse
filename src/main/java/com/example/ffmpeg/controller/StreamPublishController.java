package com.example.ffmpeg.controller;

import com.example.ffmpeg.dto.StreamPublishRequest;
import com.example.ffmpeg.dto.StreamPublishResult;
import com.example.ffmpeg.service.VideoStreamPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 视频流推送API控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/stream-publish")
@RequiredArgsConstructor
public class StreamPublishController {

    private final VideoStreamPublisherService publisherService;

    /**
     * 启动视频流AI处理和推流
     */
    @PostMapping("/start")
    public Mono<StreamPublishResult> startPublishing(@RequestBody StreamPublishRequest request) {
        log.info("🚀 启动视频流推送: {} -> {}", request.getInputSource(), request.getRtmpUrl());
        return publisherService.startStreamPublishing(request);
    }

    /**
     * 停止推流任务
     */
    @PostMapping("/stop/{taskId}")
    public Mono<StreamPublishResult> stopPublishing(@PathVariable String taskId) {
        log.info("⏹️ 停止视频流推送: {}", taskId);
        return publisherService.stopStreamPublishing(taskId);
    }

    /**
     * 获取所有活跃的推流任务
     */
    @GetMapping("/active")
    public Mono<List<StreamPublishResult>> getActiveTasks() {
        return publisherService.getActiveTasks();
    }

    /**
     * 获取特定任务的状态
     */
    @GetMapping("/{taskId}")
    public Mono<StreamPublishResult> getTaskStatus(@PathVariable String taskId) {
        return publisherService.getTaskStatus(taskId);
    }

    /**
     * 获取任务实时统计信息 (Server-Sent Events)
     */
    @GetMapping(value = "/stats/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> getTaskStats(@PathVariable String taskId) {
        return Flux.interval(Duration.ofSeconds(2))
                .flatMap(tick -> publisherService.getTaskStatus(taskId)
                        .map(result -> String.format(
                                "data: {\"taskId\":\"%s\",\"status\":\"%s\",\"frameCount\":%d,\"detectionCount\":%d,\"fps\":%.2f}\n\n",
                                result.getTaskId(),
                                result.getStatus(),
                                result.getFrameCount(),
                                result.getDetectionCount(),
                                result.getCurrentFps() != null ? result.getCurrentFps() : 0.0
                        )))
                .onErrorReturn("data: {\"error\":\"获取统计信息失败\"}\n\n");
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Mono<Map<String, Object>> healthCheck() {
        return publisherService.getActiveTasks()
                .map(tasks -> Map.of(
                        "status", "healthy",
                        "activeTasks", tasks.size(),
                        "timestamp", System.currentTimeMillis()
                ));
    }

    /**
     * 获取推荐的RTMP服务器配置
     */
    @GetMapping("/rtmp-servers")
    public Mono<Map<String, Object>> getRtmpServers() {
        return Mono.just(Map.of(
                "servers", List.of(
                        Map.of(
                                "name", "本地RTMP服务器",
                                "url", "rtmp://localhost:1935/live/stream",
                                "description", "本地搭建的RTMP服务器"
                        ),
                        Map.of(
                                "name", "阿里云直播",
                                "url", "rtmp://push.aliyunlive.com/live/{StreamName}",
                                "description", "阿里云直播推流地址"
                        ),
                        Map.of(
                                "name", "腾讯云直播",
                                "url", "rtmp://push.tcloud.com/live/{StreamName}",
                                "description", "腾讯云直播推流地址"
                        ),
                        Map.of(
                                "name", "自定义RTMP",
                                "url", "rtmp://your-server:1935/live/stream",
                                "description", "自定义RTMP服务器地址"
                        )
                ),
                "tips", List.of(
                        "确保RTMP服务器支持H.264视频编码",
                        "推荐使用TCP协议以确保稳定性",
                        "根据网络带宽调整视频比特率",
                        "建议在局域网内测试后再使用公网推流"
                )
        ));
    }

    /**
     * 获取服务器配置信息
     */
    @GetMapping("/server-info")
    public Mono<Map<String, Object>> getServerInfo() {
        return publisherService.getServerInfo();
    }

    /**
     * 测试RTMP连接
     */
    @PostMapping("/test-connection")
    public Mono<Map<String, Object>> testRtmpConnection(@RequestBody Map<String, String> request) {
        String rtmpUrl = request.get("rtmpUrl");
        return publisherService.testRtmpConnection(rtmpUrl);
    }

    /**
     * 获取支持的输入源格式
     */
    @GetMapping("/input-formats")
    public Mono<Map<String, Object>> getSupportedInputFormats() {
        return Mono.just(Map.of(
                "cameras", List.of(
                        Map.of("type", "local", "example", "0", "description", "本地摄像头(设备ID)"),
                        Map.of("type", "usb", "example", "1", "description", "USB摄像头(设备ID)")
                ),
                "streams", List.of(
                        Map.of("type", "rtsp", "example", "rtsp://admin:password@192.168.1.100:554/stream", "description", "网络摄像头RTSP流"),
                        Map.of("type", "rtmp", "example", "rtmp://server.com/live/stream", "description", "RTMP直播流"),
                        Map.of("type", "http", "example", "http://server.com/stream.m3u8", "description", "HTTP直播流(HLS)")
                ),
                "files", List.of(
                        Map.of("type", "mp4", "example", "/path/to/video.mp4", "description", "MP4视频文件"),
                        Map.of("type", "avi", "example", "/path/to/video.avi", "description", "AVI视频文件"),
                        Map.of("type", "mov", "example", "/path/to/video.mov", "description", "MOV视频文件")
                )
        ));
    }
}
