package net.bruestel.homeconnect.haproxy.service.websocket.tls;

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
public class TlsWebSocketServerEndpoint {

    private final TlsProxyService tlsProxyService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private Session session;
    @Setter(AccessLevel.PROTECTED)
    private TlsWebSocketClientEndpoint clientEndpoint;

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        tlsProxyService.appConnectionEstablished(this);

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
    public void onMessage(String message, Session session) {
        log.atDebug().log("[HA ] Forward message to home appliance.");
        clientEndpoint.sendTextMessage(message);

        tlsProxyService.receivedMessageFromApp(session.getId(), clientEndpoint.getApplianceSessionId(), message);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        var applianceSessionId = clientEndpoint != null ? clientEndpoint.getApplianceSessionId() : null;

        tlsProxyService.appConnectionClosed(session.getId(), applianceSessionId,
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

    protected void sendTextMessage(String message) {
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException e) {
                log.atError().log("Error sending message", e);
            }
        } else {
            log.atWarn().log("Jakarta WebSocket session is not open. Message not sent.");
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
