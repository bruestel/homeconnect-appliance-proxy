package net.bruestel.homeconnect.haproxy.service.websocket.tls;

import static net.bruestel.homeconnect.haproxy.service.websocket.Const.HOMECONNECT_WS_PATH;

import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

import net.bruestel.homeconnect.haproxy.service.websocket.WebSocketProxyServiceListener;

import org.apache.commons.lang3.StringUtils;
import org.conscrypt.Conscrypt;
import org.eclipse.jetty.client.GZIPContentDecoder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.io.IOException;
import java.net.URI;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import jakarta.websocket.server.ServerEndpointConfig;

@Slf4j
public class TlsProxyService {

    private static final String CONSCRYPT_PROVIDER = "Conscrypt";
    private static final String PSK_IDENTITY = "HCCOM_Local_App";
    private static final String TLSV_1_2 = "TLSv1.2";
    private static final String TLS_ECDHE_PSK_WITH_CHACHA_20_POLY_1305_SHA_256 = "TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256";
    private static final String TLS = "TLS";
    private static final Provider PROVIDER = Conscrypt.newProviderBuilder().setName(CONSCRYPT_PROVIDER).build();
    private static final String HTTP_1_1 = "http/1.1";

    private final byte[] psk;
    private final URI homeApplianceWebsocketUri;
    private final int port;
    private final WebSocketProxyServiceListener listener;

    private HttpClient httpClient;
    private WebSocketClient webSocketClient;
    private Server server;


    public TlsProxyService(URI homeApplianceWebsocketUri,
                           String base64PreSharedKey,
                           WebSocketProxyServiceListener listener,
                           int port) {
        this.homeApplianceWebsocketUri = homeApplianceWebsocketUri;
        this.listener = listener;
        this.port = port;
        this.psk = Base64.getUrlDecoder().decode(base64PreSharedKey);
    }

    @Synchronized
    public void start() throws Exception {
        if (server != null) {
            stop();
        }
        log.atInfo().log("Starting WebSocket server to proxy {} on port {}...",
                homeApplianceWebsocketUri, port);

        var sslContext = SSLContext.getInstance(TLS, PROVIDER);
        sslContext.init(
                new KeyManager[] { new ConscryptPskKeyManager(PSK_IDENTITY, psk) },
                new TrustManager[0],
                new SecureRandom());

        var sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setSslContext(sslContext);
        sslContextFactory.setIncludeCipherSuites(TLS_ECDHE_PSK_WITH_CHACHA_20_POLY_1305_SHA_256);
        sslContextFactory.setIncludeProtocols(TLSV_1_2);

        var secureRequestCustomizer = new SecureRequestCustomizer();
        secureRequestCustomizer.setSniHostCheck(false);
        secureRequestCustomizer.setSniRequired(false);

        var https = new HttpConfiguration();
        https.setSecureScheme("https");
        https.setSecurePort(port);
        https.addCustomizer(secureRequestCustomizer);

        var sslConnectionFactory = new SslConnectionFactory(sslContextFactory, HTTP_1_1);
        var httpConnectionFactory = new HttpConnectionFactory(https);

        server = new Server();
        var sslConnector = new ServerConnector(server, sslConnectionFactory, httpConnectionFactory);
        sslConnector.setPort(port);
        server.addConnector(sslConnector);

        var context = new ServletContextHandler(
                ServletContextHandler.NO_SESSIONS | ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        server.setHandler(context);

        var config = ServerEndpointConfig.Builder
                .create(TlsWebSocketServerEndpoint.class, HOMECONNECT_WS_PATH)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public <T> T getEndpointInstance(Class<T> endpointClass) {
                        if (endpointClass.equals(TlsWebSocketServerEndpoint.class)) {
                            //noinspection unchecked
                            return (T) new TlsWebSocketServerEndpoint(TlsProxyService.this);
                        }
                        throw new IllegalStateException("Unexpected endpoint: " + endpointClass);
                    }
                })
                .build();

        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) ->
                wsContainer.addEndpoint(config));

        server.start();

        destroyWebSocketClient();
        initializeWebSocketClient();
    }

    @Synchronized
    public void stop() {
        if (server != null) {
            log.atInfo().log("Stopping secure WebSocket server to proxy {} on port {}...",
                    homeApplianceWebsocketUri, port);
            try {
                server.stop();
            } catch (Exception e) {
                log.atError().log("Error stopping secure WebSocket server", e);
            }
        }

        destroyWebSocketClient();
        server = null;
    }

    protected void appConnectionEstablished(TlsWebSocketServerEndpoint serverEndpoint) {
        log.atInfo().log("[App] Connection established (appSessionId={}).", serverEndpoint.getAppSessionId());

        log.atInfo().log("[HA ] Connect to home appliance ({})...", homeApplianceWebsocketUri);
        // start client
        try {
            webSocketClient.connect(
                    new TlsWebSocketClientEndpoint(this, serverEndpoint),
                    homeApplianceWebsocketUri
            ).get();
        } catch (IOException | ExecutionException e) {
            log.atError().log("Error connecting to home appliance", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void applianceConnectionEstablished(String applianceSessionId, String appSessionId) {
        log.atInfo()
                .addArgument(applianceSessionId)
                .addArgument(appSessionId)
                .log("[HA ] Connection established (applianceSessionId={}, appSessionId={}).");
    }

    protected void receivedMessageFromAppliance(String applianceSessionId, String appSessionId, String message) {
        log.atInfo()
                .addArgument(message)
                .addArgument(applianceSessionId)
                .addArgument(appSessionId)
                .log("[HA ] Received message from home appliance (message={}, applianceSessionId={}, appSessionId={}). ");
        listener.onApplianceMessage(message, StringUtils.substringBefore(appSessionId, "-"));
    }

    protected void receivedMessageFromApp(String appSessionId, String applianceSessionId, String message) {
        log.atInfo()
                .addArgument(message)
                .addArgument(applianceSessionId)
                .addArgument(appSessionId)
                .log("[App] Received message from app (message={}, applianceSessionId={}, appSessionId={}). ");
        listener.onAppMessage(message, StringUtils.substringBefore(appSessionId, "-"));
    }

    protected void appConnectionClosed(String appSessionId, String applianceSessionId, int code, String reason) {
        log.atInfo()
                .addArgument(applianceSessionId)
                .addArgument(appSessionId)
                .addArgument(code)
                .addArgument(reason)
                .log("[App] Connection closed (applianceSessionId={}, appSessionId={}, code={}, reason={}).");
    }

    protected void applianceConnectionClosed(String applianceSessionId, String appSessionId,
                                             int code, String reason) {
        log.atInfo()
                .addArgument(applianceSessionId)
                .addArgument(appSessionId)
                .addArgument(code)
                .addArgument(reason)
                .log("[HA ] Connection closed (applianceSessionId={}, appSessionId={}, code={}, reason={}).");
    }

    private void initializeWebSocketClient() {
        if (webSocketClient == null) {
            try {
                var sslContext = SSLContext.getInstance(TLS, PROVIDER);
                sslContext.init(
                        new KeyManager[]{new ConscryptPskKeyManager(PSK_IDENTITY, psk)},
                        new TrustManager[0],
                        new SecureRandom());

                var sslContextFactory = new SslContextFactory.Client();
                sslContextFactory.setSslContext(sslContext);
                sslContextFactory.setIncludeCipherSuites(TLS_ECDHE_PSK_WITH_CHACHA_20_POLY_1305_SHA_256);
                sslContextFactory.setIncludeProtocols(TLSV_1_2);

                httpClient = new HttpClient(new HttpClientTransportOverHTTP());
                httpClient.setSslContextFactory(sslContextFactory);
                httpClient.getContentDecoderFactories().put(new GZIPContentDecoder.Factory());
                httpClient.start();

                webSocketClient = new WebSocketClient(httpClient);
                webSocketClient.start();
            } catch (Exception e) {
                throw new IllegalStateException("Could not initialize websocket client!", e);
            }
        }
    }

    private void destroyWebSocketClient() {
        if (httpClient != null) {
            try {
                httpClient.stop();
            } catch (Exception e) {
                log.atError().log("Error stopping http client", e);
            }
        }

        if (webSocketClient != null) {
            try {
                webSocketClient.stop();
            } catch (Exception e) {
                log.atError().log("Error stopping websocket client", e);
            }
        }
        webSocketClient = null;
    }
}
