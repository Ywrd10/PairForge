package com.pairforge.api.room;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InvitationTokenServiceTest {
    private final InvitationTokenService invitations = new InvitationTokenService();

    @Test void generatesIndependent256BitUrlSafeSecrets() {
        String token = invitations.generate();
        assertThat(token).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(invitations.generate());
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test void storesSha256AndRejectsDifferentTokens() {
        assertThat(invitations.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String token = invitations.generate();
        String hash = invitations.hash(token);
        assertThat(hash).matches("[a-f0-9]{64}").isNotEqualTo(token);
        assertThat(invitations.matches(token, hash)).isTrue();
        assertThat(invitations.matches(invitations.generate(), hash)).isFalse();
    }

    @Test void invitationDtosDoNotDiscloseSecretsInStringRepresentations() {
        String token = invitations.generate();
        assertThat(new RoomDtos.JoinRequest(token).toString()).isEqualTo("JoinRequest[redacted]");
        assertThat(new RoomDtos.CreatedRoom(null, token).toString()).isEqualTo("CreatedRoom[redacted]");
    }
}
