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

    private void publishActForEach(EnterOrderRepo enabled){
        for(long rqId : enabled.allOrderKeysSortedByStopPrice())
            eventPublisher.publish(new OrderActivatedEvent(rqId, enabled.findByRqId(rqId).getOrderId()));
    }

    private void handleAuctionChangeEnableds(Security security){
        EnterOrderRepo buyEnabled = security.getBuyEnabledOrders();
        EnterOrderRepo sellEnabled = security.getSellEnabledOrders();
        publishActForEach(buyEnabled);
        publishActForEach(sellEnabled);
        security.transportEnabled(Side.SELL);
        security.transportEnabled(Side.BUY);
    }

    public void handleChangeMatchingState(ChangeMatchingStateRq changeMatchingStateRq) {
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        MatchingState nextState = changeMatchingStateRq.getMatchingState();
        if (security.isAuction()) {
            MatchResult auctionResult = security.openAuction(matcher, nextState);
            if (!auctionResult.trades().isEmpty()){
                if(nextState == MatchingState.CONTINUOUS)
                    executeEnabledOrders(security);
                else
                    handleAuctionChangeEnableds(security);
            }
            security.setState(nextState);
            eventPublisher.publish(new SecurityStateChangedEvent(security.getIsin(), nextState));

            for (Trade trade : auctionResult.trades())
                eventPublisher.publish(new TradeEvent(trade.getSecurity().getIsin(), trade.getPrice(), trade.getQuantity(),
                        trade.getBuy().getOrderId(), trade.getSell().getOrderId()));
        }
        else {
            eventPublisher.publish(new SecurityStateChangedEvent(security.getIsin(), nextState));
            security.setState(nextState);
        }
    }
}
