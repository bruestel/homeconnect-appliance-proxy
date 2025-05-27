package net.bruestel.homeconnect.haproxy.service.websocket.tls;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Slf4j
@RequiredArgsConstructor
@WebSocket
public class TlsWebSocketClientEndpoint {
    private final TlsProxyService tlsProxyService;
    private final TlsWebSocketServerEndpoint serverEndpoint;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Session session;

    @OnWebSocketOpen
    public void onOpen(Session session) {
        this.session = session;
        serverEndpoint.setClientEndpoint(this);
        tlsProxyService.applianceConnectionEstablished(mapSessionId(session), serverEndpoint.getAppSessionId());

        scheduler.scheduleAtFixedRate(() -> {
            if (this.session != null && this.session.isOpen()) {
                log.atDebug().log("Sending PING to appliance ({}). ", getApplianceSessionId());
                session.sendPing(ByteBuffer.wrap(new byte[] {1}), Callback.NOOP);
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        log.atDebug().log("[App] Forward message to app.");
        serverEndpoint.sendTextMessage(message);

        tlsProxyService.receivedMessageFromAppliance(mapSessionId(session), serverEndpoint.getAppSessionId(), message);
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        tlsProxyService.applianceConnectionClosed(mapSessionId(session), serverEndpoint.getAppSessionId(),
                statusCode, reason);

        scheduler.shutdownNow();
        serverEndpoint.close();
    }

    @OnWebSocketError
    public void onError(Session session, Throwable cause) {
        log.atDebug().log("Error in secure WebSocket session: {}", cause.getMessage());
    }

    protected String getApplianceSessionId() {
        return mapSessionId(session);
    }

    private String mapSessionId(Session session) {
        return String.valueOf(session.hashCode());
    }

    protected void sendTextMessage(String message) {
        if (session != null && session.isOpen()) {
            session.sendText(message, Callback.NOOP);
        } else {
            log.atWarn().log("Jetty WebSocket session is not open. Message not sent.");
        }
    }

    protected void close() {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }
}
