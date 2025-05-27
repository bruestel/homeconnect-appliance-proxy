package net.bruestel.homeconnect.haproxy.service.websocket;

public interface WebSocketProxyServiceListener {
    void onAppMessage(String message, String sessionId);
    void onApplianceMessage(String message, String sessionId);
}
