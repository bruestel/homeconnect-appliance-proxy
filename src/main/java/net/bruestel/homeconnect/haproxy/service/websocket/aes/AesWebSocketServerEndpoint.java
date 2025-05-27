package net.bruestel.homeconnect.haproxy.service.websocket.aes;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import net.bruestel.homeconnect.haproxy.service.websocket.Const;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@Slf4j
@ServerEndpoint(Const.HOMECONNECT_WS_PATH)
@RequiredArgsConstructor
public class AesWebSocketServerEndpoint {

    private final AesProxyService aesProxyService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private Session session;
    @Setter(AccessLevel.PROTECTED)
    private AesWebSocketClientEndpoint clientEndpoint;

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        aesProxyService.appConnectionEstablished(this);

        scheduler.scheduleAtFixedRate(() -> {
            if (this.session != null && this.session.isOpen()) {
                try {
                    log.atDebug().log("Sending PING to app ({}). ", this.session.getId());
                    session.getBasicRemote().sendPing(ByteBuffer.wrap(new byte[] {1}));
                } catch (IOException e) {
                    log.atError().log("Error sending ping", e);
                }
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    @OnMessage
    public void onBinaryMessage(ByteBuffer data, Session session) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);

        log.atDebug().log("[HA ] Forward message to home appliance.");
        clientEndpoint.sendBinaryMessage(bytes);

        aesProxyService.receivedMessageFromApp(session.getId(), clientEndpoint.getApplianceSessionId(), bytes);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        var applianceSessionId = clientEndpoint != null ? clientEndpoint.getApplianceSessionId() : null;

        aesProxyService.appConnectionClosed(session.getId(), applianceSessionId,
                closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());

        if (clientEndpoint != null) {
            clientEndpoint.close();
            clientEndpoint = null;
        }

        scheduler.shutdownNow();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.atDebug().log("Error in WebSocket session ({}): {}", session.getId(), throwable.getMessage());
    }

    protected String getAppSessionId() {
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

        clientEndpoint = null;
    }
}
