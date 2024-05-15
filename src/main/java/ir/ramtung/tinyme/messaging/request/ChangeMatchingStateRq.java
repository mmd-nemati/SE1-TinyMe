package ir.ramtung.tinyme.messaging.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChangeMatchingStateRq {
    private String securityIsin;
    private MatchingState matchingState;

    public ChangeMatchingStateRq(String securityIsin, MatchingState matchingState) {
        this.securityIsin = securityIsin;
        this.matchingState = matchingState;
    }
}
