package com.yanban.api.invite;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.invite")
public class InviteCodeProperties {

    /** Whether invite code is required for registration. */
    private boolean enabled = true;

    /** Comma-separated invite codes seeded at startup. */
    private String codes;

    /** Default max uses per invite code. */
    private int maxUses = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCodes() {
        return codes;
    }

    public void setCodes(String codes) {
        this.codes = codes;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(int maxUses) {
        // The first release intentionally keeps invitation sharing small and predictable.
        this.maxUses = Math.min(5, Math.max(1, maxUses));
    }

    /**
     * Parses the comma-separated codes string into a list of trimmed, non-blank codes.
     */
    public List<String> parseCodes() {
        if (codes == null || codes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(codes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
