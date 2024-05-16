package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.EnterOrderRepo;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

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

    void executeEnabledOrders(Security security){
        execBuyAndSell(security, Side.BUY);
        execBuyAndSell(security, Side.SELL);
    }

    void execBuyAndSell(Security security, Side side){
        EnterOrderRepo orders;
        if(side == Side.BUY)
            orders = security.getBuyEnabledOrders().makeCopy();
        else
            orders = security.getSellEnabledOrders().makeCopy();

        if(orders != null) {
            for (long reqId : orders.allOrderKeysSortedByStopPrice())
                executeTheEnabled(orders.findByRqId(reqId), reqId, side);
        }

        if(isEnabledOver(side, security))
            return;

        execBuyAndSell(enterRq, side);
    }

    void executeTheEnabled(Order order, long reqId, Side side){
        Security security = order.getSecurity();

        security.removeEnabledOrder(reqId, side);
        eventPublisher.publish(new OrderActivatedEvent(reqId, order.getOrderId()));
        order.setStopPriceZero();

        MatchResult matchResult = security.handleEnterOrder(order, reqId, matcher);
        if (!matchResult.trades().isEmpty())
            applyExecutionUpdates(security, order.getOrderId(), reqId, matchResult);
    }

    void applyExecutionUpdates(Security security, long orderId, long reqId, MatchResult matchResult){

        eventPublisher.publish(new OrderExecutedEvent(reqId, orderId,
                matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));

        security.updateLastTradePrice(matchResult.trades().getLast().getPrice());
        security.handleDisabledOrders();
    }

    private boolean isEnabledOver(Side side, Security security){
        return(
                (side == Side.BUY && (security.getBuyEnabledOrders().theSize() == 0))
                        ||
                        (side == Side.SELL && (security.getSellEnabledOrders().theSize() == 0))
        );
    }
}
