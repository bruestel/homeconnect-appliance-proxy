package net.bruestel.homeconnect.haproxy.service.websocket.aes;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;

@Slf4j
@ClientEndpoint
@RequiredArgsConstructor
public class AesWebSocketClientEndpoint {
    private final AesProxyService aesProxyService;
    private final AesWebSocketServerEndpoint serverEndpoint;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Session session;

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        serverEndpoint.setClientEndpoint(this);
        aesProxyService.applianceConnectionEstablished(session.getId(), serverEndpoint.getAppSessionId());

        scheduler.scheduleAtFixedRate(() -> {
            if (this.session != null && this.session.isOpen()) {
                try {
                    log.atDebug().log("Sending PING to appliance ({}). ", this.session.getId());
                    session.getBasicRemote().sendPing(ByteBuffer.wrap(new byte[] {1}));
                } catch (IOException e) {
                    log.atError().log("Error sending ping", e);
                }
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    @OnMessage
    public void onBinaryMessage(ByteBuffer message, Session session) {
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);

        log.atDebug().log("[App] Forward message to app.");
        serverEndpoint.sendBinaryMessage(bytes);

        aesProxyService.receivedMessageFromAppliance(session.getId(), serverEndpoint.getAppSessionId(), bytes);
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        aesProxyService.applianceConnectionClosed(session.getId(), serverEndpoint.getAppSessionId(),
                reason.getCloseCode().getCode(), reason.getReasonPhrase());

        scheduler.shutdownNow();
        serverEndpoint.close();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.atDebug().log("Error in WebSocket session ({}): {}", session.getId(), throwable.getMessage());
    }

    protected String getApplianceSessionId() {
        return session.getId();
    }

    protected void sendBinaryMessage(byte[] message) {
        if (session != null && session.isOpen()) {
            session.getAsyncRemote().sendBinary(ByteBuffer.wrap(message));
        } else {
            log.atWarn().log("Session is not open. Message not sent.");
        }
    }

    protected void close() {
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.atError().log("Error closing session", e);
            }
        }
    }
}
