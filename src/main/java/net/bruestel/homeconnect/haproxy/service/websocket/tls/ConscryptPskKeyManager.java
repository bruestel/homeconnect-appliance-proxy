package net.bruestel.homeconnect.haproxy.service.websocket.tls;

import org.conscrypt.PSKKeyManager;

import java.net.Socket;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLEngine;

@SuppressWarnings("deprecation")
public class ConscryptPskKeyManager implements PSKKeyManager {

    private static final String PSK_ALGORITHM = "PSK";

    private final String identityHint;
    private final byte[] key;

    public ConscryptPskKeyManager(String identityHint, byte[] key) {
        this.identityHint = identityHint;
        this.key = key;
    }

    @Override
    public String chooseServerKeyIdentityHint(Socket socket) {
        return identityHint;
    }

    @Override
    public String chooseServerKeyIdentityHint(SSLEngine sslEngine) {
        return identityHint;
    }

    @Override
    public String chooseClientKeyIdentity(String s, Socket socket) {
        return identityHint;
    }

    @Override
    public String chooseClientKeyIdentity(String s, SSLEngine sslEngine) {
        return identityHint;
    }

    @Override
    public SecretKey getKey(String s, String s1, Socket socket) {
        return new SecretKeySpec(key, PSK_ALGORITHM);
    }

    @Override
    public SecretKey getKey(String s, String s1, SSLEngine sslEngine) {
        return new SecretKeySpec(key, PSK_ALGORITHM);
    }
}
