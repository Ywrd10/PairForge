package com.pairforge.api.user;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EmailTest {
    @Test
    void normalizesWithoutProviderSpecificRewriting() {
        assertThat(Email.normalize("  Alice.Example+tag@EXAMPLE.COM  "))
                .isEqualTo("alice.example+tag@example.com");
    }

    @Test
    void rejectsMissingEmptyAndOversizedEmail() {
        assertThatNullPointerException().isThrownBy(() -> Email.normalize(null));
        assertThatIllegalArgumentException().isThrownBy(() -> Email.normalize("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> Email.normalize("a".repeat(255)));
        assertThat(Email.normalize("a".repeat(254))).hasSize(254);
    }

    @Test
    void doesNotDependOnMachineLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(Email.normalize("I@EXAMPLE.COM")).isEqualTo("i@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
