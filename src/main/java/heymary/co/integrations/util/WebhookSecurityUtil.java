package heymary.co.integrations.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public class WebhookSecurityUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * Verify HMAC signature for Dutchie webhooks
     */
    public static boolean verifyHmacSignature(String payload, String signature, String secret) {
        if (signature == null || secret == null) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedSignature = bytesToHex(hash);
            
            // Dutchie typically sends signature as "sha256=<hash>"
            String expectedSignature = signature.startsWith("sha256=") 
                    ? signature.substring(7) 
                    : signature;
            
            return computedSignature.equalsIgnoreCase(expectedSignature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}

