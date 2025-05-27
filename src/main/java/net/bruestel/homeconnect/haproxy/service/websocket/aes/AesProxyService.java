package net.bruestel.homeconnect.haproxy.service.websocket.aes;

import static net.bruestel.homeconnect.haproxy.service.websocket.Const.HOMECONNECT_WS_PATH;

import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

import net.bruestel.homeconnect.haproxy.service.websocket.WebSocketProxyServiceListener;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerEndpointConfig;

@Slf4j
public class AesProxyService {
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final byte[] key;
    private final byte[] iv;

    private final URI homeApplianceWebsocketUri;
    private final int port;

    private final Map<String, AesState> aesStateMap;
    private final WebSocketProxyServiceListener listener;

    private Server server;

    public AesProxyService(URI homeApplianceWebsocketUri,
                           String base64EncodedKey,
                           String base64EncodedInitializationVector,
                           WebSocketProxyServiceListener listener,
                           int port) {
        this.aesStateMap = new ConcurrentHashMap<>();
        this.homeApplianceWebsocketUri = homeApplianceWebsocketUri;
        this.listener = listener;
        this.port = port;
        this.key = Base64.getUrlDecoder().decode(base64EncodedKey);
        this.iv = Base64.getUrlDecoder().decode(base64EncodedInitializationVector);
    }

    @Synchronized
    public void start() throws Exception {
        if (server != null) {
            stop();
        }
        log.atInfo().log("Starting WebSocket server to proxy {} on port {}...",
                homeApplianceWebsocketUri, port);
        server = new Server(port);

        ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.NO_SESSIONS | ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        server.setHandler(context);

        ServerEndpointConfig config = ServerEndpointConfig.Builder
                .create(AesWebSocketServerEndpoint.class, HOMECONNECT_WS_PATH)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public <T> T getEndpointInstance(Class<T> endpointClass) {
                        if (endpointClass.equals(AesWebSocketServerEndpoint.class)) {
                            //noinspection unchecked
                            return (T) new AesWebSocketServerEndpoint(AesProxyService.this);
                        }
                        throw new IllegalStateException("Unexpected endpoint: " + endpointClass);
                    }
                })
                .build();

        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) ->
                wsContainer.addEndpoint(config));

        server.start();
    }

    @Synchronized
    public void stop() {
        if (server != null) {
            log.atInfo().log("Stopping WebSocket server to proxy {} on port {}...",
                    homeApplianceWebsocketUri, port);
            try {
                server.stop();
            } catch (Exception e) {
                log.atError().log("Error stopping server", e);
            }
        }
        aesStateMap.clear();
        server = null;
    }

    protected void appConnectionEstablished(AesWebSocketServerEndpoint serverEndpoint) {
        log.atInfo().log("[App] Connection established (appSessionId={}).", serverEndpoint.getAppSessionId());

        log.atInfo().log("[HA ] Connect to home appliance ({})...", homeApplianceWebsocketUri);
        // start client
        var container = ContainerProvider.getWebSocketContainer();
        try {
            var clientEndpoint = new AesWebSocketClientEndpoint(this, serverEndpoint);
            container.connectToServer(clientEndpoint, homeApplianceWebsocketUri);
        } catch (DeploymentException | IOException e) {
            throw new IllegalStateException("Error connecting to home appliance", e);
        }
    }

    protected void applianceConnectionEstablished(String applianceSessionId, String appSessionId) {
        log.atInfo()
                .addArgument(applianceSessionId)
                .addArgument(appSessionId)
                .log("[HA ] Connection established (applianceSessionId={}, appSessionId={}).");

        // AES state
        var aesState = new AesState(key, iv);
        aesStateMap.put(appSessionId, aesState);
        aesStateMap.put(applianceSessionId, aesState);
    }

    protected void receivedMessageFromAppliance(String applianceSessionId, String appSessionId, byte[] message) {
        try {
            var decryptedMessage = new String(decryptMessage(message, false, aesStateMap.get(applianceSessionId)), StandardCharsets.UTF_8);
            log.atInfo()
                    .addArgument(decryptedMessage)
                    .addArgument(applianceSessionId)
                    .addArgument(appSessionId)
                    .log("[HA ] Received message from home appliance (message={}, applianceSessionId={}, appSessionId={}). ");
            listener.onApplianceMessage(decryptedMessage, StringUtils.substringBefore(appSessionId, "-"));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.atError().log("Error decrypting message from appliance", e);
        }
    }

    protected void receivedMessageFromApp(String appSessionId, String applianceSessionId, byte[] message) {
        try {
            var decryptedMessage = new String(decryptMessage(message, true, aesStateMap.get(appSessionId)), StandardCharsets.UTF_8);
            log.atInfo()
                    .addArgument(decryptedMessage)
                    .addArgument(applianceSessionId)
                    .addArgument(appSessionId)
                    .log("[App] Received message from app (message={}, applianceSessionId={}, appSessionId={}).");
            listener.onAppMessage(decryptedMessage, StringUtils.substringBefore(appSessionId, "-"));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.atError().log("Error decrypting message from app", e);
        }
    }

    protected void appConnectionClosed(String appSessionId, String applianceSessionId, int code, String reason) {
        log.atInfo()
                .addArgument(applianceSessionId)
                .addArgument(appSessionId)
                .addArgument(code)
                .addArgument(reason)
                .log("[App] Connection closed (applianceSessionId={}, appSessionId={}, code={}, reason={}).");

        if (applianceSessionId != null) {
            aesStateMap.remove(applianceSessionId);
        }
        aesStateMap.remove(appSessionId);
    }

    protected void applianceConnectionClosed(String applianceSessionId, String appSessionId,
                                             int code, String reason) {
        log.atInfo()
                .addArgument(applianceSessionId)
                .addArgument(appSessionId)
                .addArgument(code)
                .addArgument(reason)
                .log("[HA ] Connection closed (applianceSessionId={}, appSessionId={}, code={}, reason={}).");

        aesStateMap.remove(appSessionId);
        aesStateMap.remove(applianceSessionId);
    }

    protected static byte[] hmac(byte[] key, byte[] msg) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_SHA_256);
        SecretKeySpec secretKey = new SecretKeySpec(key, HMAC_SHA_256);
        mac.init(secretKey);
        return mac.doFinal(msg);
    }

    private byte[] decryptMessage(byte[] buf, boolean appMessage, AesState aesState) throws NoSuchAlgorithmException, InvalidKeyException {
        // Split the message into the encrypted message and the first 16 bytes of the HMAC
        byte[] encryptedMessage = Arrays.copyOfRange(buf, 0, buf.length - 16);
        byte[] applianceHmac = Arrays.copyOfRange(buf, buf.length - 16, buf.length);

        byte direction = appMessage ? (byte)0x45 : (byte)0x43;
        byte[] lastHmac = appMessage ? aesState.lastTxHmac : aesState.lastRxHmac;

        // Compute the expected HMAC on the encrypted message
        byte[] directionAndLastHmac = concatenateByteArrays(new byte[] { direction }, lastHmac);
        byte[] ourHmac = createHmacMessage(directionAndLastHmac, encryptedMessage, aesState.macKey);

        if (!Arrays.equals(applianceHmac, ourHmac)) {
            log.error("HMAC failure! appliance={} ourHmac={}, msgLength={}", Hex.encodeHexString(applianceHmac),
                    Hex.encodeHexString(ourHmac), buf.length);
        }

        if (appMessage) {
            aesState.lastTxHmac = applianceHmac;
        } else {
            aesState.lastRxHmac = applianceHmac;
        }

        // Decrypt the message with CBC, so the last message block is mixed in
        byte[] msg;
        msg = appMessage ? aesState.aesAppDecrypt.update(encryptedMessage) : aesState.aesApplianceDecrypt.update(encryptedMessage);

        // Check for padding and trim it off the end
        int padLen = msg[msg.length - 1] & 0xFF; // Convert to unsigned integer
        if (msg.length < padLen) {
            log.error("Padding error! {}", Hex.encodeHexString(msg));
        }
        log.trace("Padding length={}", padLen);

        return Arrays.copyOfRange(msg, 0, msg.length - padLen);
    }

    // HMAC an inbound or outbound message, chaining the last HMAC
    private byte[] createHmacMessage(byte[] direction, byte[] encMsg, byte[] macKey)
            throws NoSuchAlgorithmException, InvalidKeyException {
        byte[] hmacMsg = concatenateByteArrays(iv, direction, encMsg);
        byte[] fullHmac = hmac(macKey, hmacMsg);
        return Arrays.copyOfRange(fullHmac, 0, 16);
    }

    private byte[] concatenateByteArrays(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }
        byte[] result = new byte[totalLength];
        int currentIndex = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, currentIndex, array.length);
            currentIndex += array.length;
        }
        return result;
    }
}
