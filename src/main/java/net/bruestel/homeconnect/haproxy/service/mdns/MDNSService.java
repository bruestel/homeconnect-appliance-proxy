package net.bruestel.homeconnect.haproxy.service.mdns;


import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import net.bruestel.homeconnect.haproxy.service.mdns.model.ConnectionType;
import net.bruestel.homeconnect.haproxy.service.mdns.model.HomeAppliance;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

@Slf4j
public class MDNSService {
    private static final String HOMECONNECT_SERVICE_TYPE = "_homeconnect._tcp.local.";

    private final List<JmDNS> jmdnsList;
    private final ServiceListener serviceListener;

    public MDNSService() {
        this(null);
    }

    public MDNSService(HomeApplianceListener homeApplianceListener) {
        jmdnsList = new ArrayList<>();

        serviceListener = new ServiceListener() {

            @Override
            public void serviceAdded(ServiceEvent event) {
                log.atDebug().log("Service added: {}", event.getInfo());
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
                log.atDebug().log("Service removed: {}", event.getInfo());
                if (homeApplianceListener != null) {
                    homeApplianceListener.onLostHomeAppliance(map(event));
                }
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                log.atDebug().log("Service resolved: {}", event);
                var homeAppliance = map(event);
                if (homeApplianceListener != null && StringUtils.isNoneEmpty(homeAppliance.getId())) {
                    homeApplianceListener.onNewOrUpdatedHomeAppliance(map(event));
                }
            }
        };

        getAllExternalIPAddresses()
                .forEach(address -> {
                    try {
                        jmdnsList.add(JmDNS.create(address));
                        log.atInfo().log("JmDNS created for {}", address);
                    } catch (IOException e) {
                        log.atError().log("Error creating JmDNS for {}", address, e);
                        throw new IllegalStateException(e);
                    }
                });
    }

    public void startNetworkScan() {
        stopNetworkScan();
        jmdnsList.forEach(jmDNS -> jmDNS.addServiceListener(HOMECONNECT_SERVICE_TYPE, serviceListener));
    }

    public void stopNetworkScan() {
        jmdnsList.forEach(jmDNS -> jmDNS.removeServiceListener(HOMECONNECT_SERVICE_TYPE, serviceListener));
    }

    @SneakyThrows
    public void registerProxyService(HomeAppliance homeAppliance, int proxyPort) {
        jmdnsList.forEach(jmDNS -> {
            try {
                log.atInfo().log("Registering mDNS Service");
                var serviceInfo = ServiceInfo.create(
                        HOMECONNECT_SERVICE_TYPE,
                        homeAppliance.getId() + " Proxy" ,
                        "",
                        proxyPort,
                        10,
                        0,
                        true,
                        homeAppliance.getText()
                );


                jmDNS.registerService(serviceInfo);
                log.atInfo().log("Registered mDNS Service: {}", serviceInfo.getName());
            } catch (IOException e) {
                log.atError().log("Error registering mDNS Service", e);
                throw new IllegalStateException(e);
            }
        });
    }

    public void unregisterAllProxyServices() {
        jmdnsList.forEach(JmDNS::unregisterAllServices);
    }

    public void close() {
        log.atInfo().log("Stopping mDNS");
        jmdnsList.forEach(jmDNS -> {
            jmDNS.unregisterAllServices();
            try {
                Thread.sleep(Duration.ofSeconds(2));
            } catch (InterruptedException ignored) {}
            try {
                jmDNS.close();
            } catch (IOException e) {
                log.atError().log("Error closing JmDNS", e);
            }
        });
    }

    @SneakyThrows
    protected static List<InetAddress> getAllExternalIPAddresses() {
        List<InetAddress> externalIps = new ArrayList<>();

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();

            if (!iface.isUp() || iface.isLoopback()) continue;

            Enumeration<InetAddress> addresses = iface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();

                if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                    externalIps.add(addr);
                }
            }
        }

        return externalIps;
    }

    protected HomeAppliance map(ServiceEvent event) {
        var serviceInfo = event.getInfo();
        var connectionType = Boolean.parseBoolean(serviceInfo.getPropertyString("tls")) ? ConnectionType.TLS : ConnectionType.AES;
        var id = serviceInfo.getPropertyString("id");
        var brand = serviceInfo.getPropertyString("brand");
        var type = serviceInfo.getPropertyString("type");
        var vib = serviceInfo.getPropertyString("vib");

        return HomeAppliance.builder()
                .id(id)
                .brand(brand)
                .type(type)
                .vib(vib)
                .addressSet(Arrays.stream(serviceInfo.getInetAddresses()).collect(java.util.stream.Collectors.toSet()))
                .port(serviceInfo.getPort())
                .text(serviceInfo.getTextBytes())
                .connectionType(connectionType)
                .build();
    }
}
