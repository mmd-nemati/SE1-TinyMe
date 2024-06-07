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

    private void handleAuctionChangeEnables(Security security){
        publishActForEach(security.getQueueInfo().getEnabledOrders(Side.BUY));
        publishActForEach(security.getQueueInfo().getEnabledOrders(Side.SELL));
        security.transportEnabled(Side.SELL);
        security.transportEnabled(Side.BUY);
    }

    private void applyTradeEffects(MatchResult auctionResult, MatchingState nextState, Security security){
        if (!auctionResult.trades().isEmpty()){
            if(nextState == MatchingState.CONTINUOUS)
                executeEnabledOrders(security);
            else
                handleAuctionChangeEnables(security);
        }
    }

    private void publishTradeEvents(MatchResult auctionResult) {
        for (Trade trade : auctionResult.trades())
            eventPublisher.publish(new TradeEvent(trade.getSecurity().getIsin(), trade.getPrice(), trade.getQuantity(),
                    trade.getBuy().getOrderId(), trade.getSell().getOrderId()));
    }

    private void handleAuctionChange(MatchingState nextState, Security security){
        MatchResult auctionResult = security.openAuction(matcher);
        applyTradeEffects(auctionResult, nextState, security);
        publishTradeEvents(auctionResult);
    }

    public void handleChangeMatchingState(ChangeMatchingStateRq changeMatchingStateRq) {
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        MatchingState nextState = changeMatchingStateRq.getMatchingState();
        if (security.isAuction())
            handleAuctionChange(nextState, security);

        eventPublisher.publish(new SecurityStateChangedEvent(security.getIsin(), nextState));
        security.setState(nextState);
    }
}
