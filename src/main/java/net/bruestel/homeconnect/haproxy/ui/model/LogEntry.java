package net.bruestel.homeconnect.haproxy.ui.model;

import lombok.Value;

import java.time.ZonedDateTime;

@Value
public class LogEntry {
    ZonedDateTime timestamp;
    String sessionId;
    Sender sender;
    Object message;
}
