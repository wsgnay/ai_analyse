package com.example.ffmpeg.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;

@Slf4j
@Service
public class VideoStreamService implements WebSocketHandler {

    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket连接已建立: {}", session.getId());

        // 不发送连接确认消息，保持静默
        return session.receive()
                .doOnNext(message -> {
                    // 处理客户端可能发送的消息（如果需要）
                    log.debug("收到客户端消息: {}", message.getPayloadAsText());
                })
                .doOnCancel(() -> {
                    sessions.remove(session);
                    log.info("WebSocket连接已关闭: {}", session.getId());
                })
                .doOnError(error -> {
                    sessions.remove(session);
                    log.info("WebSocket连接错误: {}", session.getId(), error);
                })
                .doOnTerminate(() -> {
                    sessions.remove(session);
                    log.info("WebSocket连接终止: {}", session.getId());
                })
                .then();
    }

    /**
     * 向所有连接的客户端发送视频帧
     * @param image 视频帧图像
     */
    public void broadcastFrame(BufferedImage image) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            // 将图像转换为JPEG格式的字节数组
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpeg", baos);
            byte[] imageBytes = baos.toByteArray();

            // 编码为Base64字符串
            String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);

            // 向所有客户端广播
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.send(Mono.just(session.textMessage(base64Image))).subscribe();
                    } catch (Exception e) {
                        log.error("发送视频帧失败: {}", session.getId(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("处理视频帧时出错", e);
        }
    }

    /**
     * 检查是否有活跃的WebSocket连接
     * @return 如果有连接返回true，否则返回false
     */
    public boolean hasActiveConnections() {
        return !sessions.isEmpty();
    }

    @Override
    public java.util.List<String> getSubProtocols() {
        return java.util.Collections.emptyList();
    }
}
