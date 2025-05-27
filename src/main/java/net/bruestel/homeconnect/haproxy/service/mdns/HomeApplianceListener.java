package net.bruestel.homeconnect.haproxy.service.mdns;

import net.bruestel.homeconnect.haproxy.service.mdns.model.HomeAppliance;

public interface HomeApplianceListener {
    void onNewOrUpdatedHomeAppliance(HomeAppliance homeAppliance);
    void onLostHomeAppliance(HomeAppliance homeAppliance);
}
