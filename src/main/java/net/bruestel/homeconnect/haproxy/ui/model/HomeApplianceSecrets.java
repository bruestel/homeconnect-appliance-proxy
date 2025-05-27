package net.bruestel.homeconnect.haproxy.ui.model;

import lombok.Value;

@Value
public class HomeApplianceSecrets {
    String psk;
    String key;
    String iv;
}
