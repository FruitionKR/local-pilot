package fruition.agent.service;

import fruition.aihistory.exception.InvalidCallbackTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AgentServiceTokenVerifier {
    private final byte[] serviceToken;

    public AgentServiceTokenVerifier(@Value("${app.agent.service-token}") String serviceToken) {
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String token) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), serviceToken)) {
            throw new InvalidCallbackTokenException();
        }
    }
}
