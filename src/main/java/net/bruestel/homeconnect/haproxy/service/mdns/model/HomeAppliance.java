package net.bruestel.homeconnect.haproxy.service.mdns.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;

import java.net.InetAddress;
import java.util.Set;

@Value
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HomeAppliance {
    @EqualsAndHashCode.Include
    String id;
    String brand;
    String type;
    String vib;
    Set<InetAddress> addressSet;
    int port;
    ConnectionType connectionType;
    @ToString.Exclude
    byte[] text;
}
