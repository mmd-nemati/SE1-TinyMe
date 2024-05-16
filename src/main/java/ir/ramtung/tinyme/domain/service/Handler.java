package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

@Service
abstract class Handler {
        SecurityRepository securityRepository;
        BrokerRepository brokerRepository;
        ShareholderRepository shareholderRepository;
        EventPublisher eventPublisher;
        Matcher matcher;

        Handler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
            this.securityRepository = securityRepository;
            this.brokerRepository = brokerRepository;
            this.shareholderRepository = shareholderRepository;
            this.eventPublisher = eventPublisher;
            this.matcher = matcher;
        }
}
