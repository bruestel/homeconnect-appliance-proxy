package net.bruestel.homeconnect.haproxy;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import net.bruestel.homeconnect.haproxy.service.mdns.HomeApplianceListener;
import net.bruestel.homeconnect.haproxy.service.mdns.MDNSService;
import net.bruestel.homeconnect.haproxy.service.mdns.model.ConnectionType;
import net.bruestel.homeconnect.haproxy.service.mdns.model.HomeAppliance;
import net.bruestel.homeconnect.haproxy.service.websocket.Const;
import net.bruestel.homeconnect.haproxy.service.websocket.WebSocketProxyServiceListener;
import net.bruestel.homeconnect.haproxy.service.websocket.aes.AesProxyService;
import net.bruestel.homeconnect.haproxy.service.websocket.tls.TlsProxyService;
import net.bruestel.homeconnect.haproxy.ui.LogView;
import net.bruestel.homeconnect.haproxy.ui.ProxyConfigurationView;
import net.bruestel.homeconnect.haproxy.ui.TableView;
import net.bruestel.homeconnect.haproxy.ui.model.LogEntry;
import net.bruestel.homeconnect.haproxy.ui.model.Sender;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.ServerSocket;
import java.net.URI;
import java.time.ZonedDateTime;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

@Slf4j
public class HomeConnectApplianceProxyApplication extends Application implements HomeApplianceListener {

    private static final int STAGE_WIDTH = 1024;
    private static final int STAGE_HEIGHT = 600;

    private final MDNSService mdnsService = new MDNSService(this);
    private AesProxyService aesProxyService;
    private TlsProxyService tlsProxyService;

    private TableView tableView;

    @Override
    public void start(Stage stage) throws Exception {
        // start mDNS listener
        mdnsService.startNetworkScan();

        tableView = new TableView();
        Scene mainScene = new Scene(tableView, STAGE_WIDTH, STAGE_HEIGHT);
        stage.setScene(mainScene);
        stage.setTitle("Home Connect - Home Appliance Proxy");
        stage.show();

        tableView.setHomeApplianceSelectedAction(homeAppliance -> {
            // stop scan
            mdnsService.stopNetworkScan();

            log.atInfo().log("Enter setup screen for {}", homeAppliance);
            var proxyConfigurationView = new ProxyConfigurationView(homeAppliance,
                    homeApplianceSecrets -> {
                        var logView = new LogView(stage,
                                homeAppliance,
                                onCancel -> {
                                    stage.setScene(mainScene);
                                    tableView.getHomeAppliances().clear();

                                    new Thread(() -> {
                                        mdnsService.unregisterAllProxyServices();
                                        if (aesProxyService != null) {
                                            aesProxyService.stop();
                                            aesProxyService = null;
                                        }
                                        if (tlsProxyService != null) {
                                            tlsProxyService.stop();
                                            tlsProxyService = null;
                                        }
                                        mdnsService.startNetworkScan();
                                    }).start();
                                });
                        Scene logViewScene = new Scene(logView, STAGE_WIDTH, STAGE_HEIGHT);
                        stage.setScene(logViewScene);

                        // message listener
                        var messageListener = new WebSocketProxyServiceListener() {
                            @Override
                            public void onAppMessage(String message, String sessionId) {
                                try {
                                    logView.getLogEntries().add(new LogEntry(ZonedDateTime.now(),
                                            sessionId,
                                            Sender.APP,
                                            new ObjectMapper().readValue(message, Object.class)));
                                } catch (JsonProcessingException ex) {
                                    log.atError().log("Error parsing app message: {}", message, ex);
                                }
                            }

                            @Override
                            public void onApplianceMessage(String message, String sessionId) {
                                try {
                                    logView.getLogEntries().add(new LogEntry(ZonedDateTime.now(),
                                            sessionId,
                                            Sender.HOME_APPLIANCE,
                                            new ObjectMapper().readValue(message, Object.class)));
                                } catch (JsonProcessingException ex) {
                                    log.atError().log("Error parsing application message: {}", message, ex);
                                }
                            }
                        };

                        // start proxy service
                        var proxyPort = getFreePort();
                        Thread proxyThread = new Thread(() -> {
                            if (aesProxyService != null) {
                                aesProxyService.stop();
                            }
                            if (tlsProxyService != null) {
                                tlsProxyService.stop();
                            }

                            if (ConnectionType.TLS.equals(homeAppliance.getConnectionType())) {
                                tlsProxyService = new TlsProxyService(
                                        getHomeApplianceWebsocketUri(homeAppliance, true),
                                        homeApplianceSecrets.getPsk(),
                                        messageListener,
                                        proxyPort);
                                try {
                                    tlsProxyService.start();
                                } catch (Exception ex) {
                                    log.atError().log("Error starting TLS proxy service: {}", ex.getMessage(), ex);
                                }
                            } else {
                                aesProxyService = new AesProxyService(
                                        getHomeApplianceWebsocketUri(homeAppliance, false),
                                        homeApplianceSecrets.getKey(),
                                        homeApplianceSecrets.getIv(),
                                        messageListener,
                                        proxyPort);
                                try {
                                    aesProxyService.start();
                                } catch (Exception ex) {
                                    log.atError().log("Error starting AES proxy service: {}", ex.getMessage(), ex);
                                }
                            }
                        });
                        proxyThread.setDaemon(true);
                        proxyThread.start();

                        mdnsService.registerProxyService(homeAppliance, proxyPort);
                    },
                    onCancel -> {
                        stage.setScene(mainScene);
                        tableView.getHomeAppliances().clear();
                        mdnsService.startNetworkScan();
                    });
            Scene newScene = new Scene(proxyConfigurationView, STAGE_WIDTH, STAGE_HEIGHT);
            stage.setScene(newScene);
        });

        // global close handler
        stage.setOnCloseRequest(event -> {
            new Thread(() -> {
                log.atInfo().log("Stopping mDNS...");
                mdnsService.stopNetworkScan();
                mdnsService.close();

                if (aesProxyService != null) {
                    aesProxyService.stop();
                    aesProxyService = null;
                }

                if (tlsProxyService != null) {
                    tlsProxyService.stop();
                    tlsProxyService = null;
                }
            }).start();
            Platform.exit();
        });
    }

    @Override
    public void onNewOrUpdatedHomeAppliance(HomeAppliance homeAppliance) {
        Platform.runLater(() ->  {
            var appliances = tableView.getHomeAppliances();
            if (appliances.contains(homeAppliance)) {
                appliances.stream()
                        .filter(ha -> ha.equals(homeAppliance))
                        .findFirst()
                        .ifPresent(existingHomeAppliance -> {
                                    existingHomeAppliance
                                            .getAddressSet().addAll(homeAppliance.getAddressSet());
                                    tableView.refresh();
                                }
                        );
            } else {
                appliances.add(homeAppliance);
            }
        });
    }

    @Override
    public void onLostHomeAppliance(HomeAppliance homeAppliance) {
        Platform.runLater(() -> tableView.getHomeAppliances().remove(homeAppliance));
    }

    private int getFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private URI getHomeApplianceWebsocketUri(HomeAppliance homeAppliance, boolean tls) {
        // primary ip
        var address = homeAppliance.getAddressSet()
                .stream()
                .min((a, b) -> {
                    boolean aIsIPv4 = a instanceof Inet4Address;
                    boolean bIsIPv4 = b instanceof Inet4Address;
                    return Boolean.compare(!aIsIPv4, !bIsIPv4);
                })
                .orElseThrow();

        var host =  (address instanceof Inet6Address) ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
        return URI.create((tls ? "wss://" : "ws://") + host + Const.HOMECONNECT_WS_PATH);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
