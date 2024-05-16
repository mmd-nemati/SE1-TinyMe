package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.*;
import ir.ramtung.tinyme.repository.*;
import org.springframework.stereotype.Service;

@Service
public class MatchingStateHandler extends Handler{

    public MatchingStateHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        super(securityRepository, brokerRepository, shareholderRepository, eventPublisher, matcher);
    }

    public void handleChangeMatchingState(ChangeMatchingStateRq changeMatchingStateRq) {
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        if (security.isAuction()) {
            MatchResult auctionResult = security.handleAuction(matcher);
            if (!auctionResult.trades().isEmpty())
                executeEnabledOrders(security);
            security.setState(changeMatchingStateRq.getMatchingState());
            eventPublisher.publish(new SecurityStateChangedEvent(security.getIsin(), changeMatchingStateRq.getMatchingState()));

            for (Trade trade : auctionResult.trades())
                eventPublisher.publish(new TradeEvent(trade.getSecurity().getIsin(), trade.getPrice(), trade.getQuantity(),
                        trade.getBuy().getOrderId(), trade.getSell().getOrderId()));

        }
        else {
            eventPublisher.publish(new SecurityStateChangedEvent(security.getIsin(), changeMatchingStateRq.getMatchingState()));
            security.setState(changeMatchingStateRq.getMatchingState());
        }
    }
}
