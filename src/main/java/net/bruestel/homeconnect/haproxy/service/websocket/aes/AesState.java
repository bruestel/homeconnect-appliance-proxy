package net.bruestel.homeconnect.haproxy.service.websocket.aes;

import static net.bruestel.homeconnect.haproxy.service.websocket.aes.AesProxyService.hmac;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Slf4j
public class AesState {

    protected static final String AES_CBC_NO_PADDING = "AES/CBC/NoPadding";
    protected static final String AES = "AES";
    protected static final String ENC = "ENC";
    protected static final String MAC = "MAC";

    protected final Cipher aesAppDecrypt;
    protected final Cipher aesApplianceDecrypt;
    protected final byte[] macKey;

    protected byte[] lastRxHmac;
    protected byte[] lastTxHmac;

    public AesState(byte[] key, byte[] iv) {
        try {
            // init AES
            byte[] encryptionKey = hmac(key, ENC.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, AES);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            aesAppDecrypt = Cipher.getInstance(AES_CBC_NO_PADDING);
            aesAppDecrypt.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            aesApplianceDecrypt = Cipher.getInstance(AES_CBC_NO_PADDING);
            aesApplianceDecrypt.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            // init HMAC
            macKey = hmac(key, MAC.getBytes(StandardCharsets.UTF_8));
            lastRxHmac = new byte[16];
            lastTxHmac = new byte[16];
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidAlgorithmParameterException |
                 InvalidKeyException e) {
            log.atError().log("Error initializing AES", e);
            throw new IllegalStateException(e);
        }
    }
}
