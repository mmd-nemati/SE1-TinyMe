package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import ir.ramtung.tinyme.repository.*;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    Matcher matcher;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
    }

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult;
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, matcher);
            else
                matchResult = security.updateOrder(enterOrderRq, matcher);

            if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
                return;
            }
            if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS) {
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
                return;
            }
            if (matchResult.outcome() == MatchingOutcome.NOT_SATISFY_MIN_EXEC) {
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
                return;
            }
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            else
                eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            if(matchResult.outcome() == MatchingOutcome.EXECUTED && enterOrderRq.isStopLimitOrderRq()) {
                applyActivationEffects(enterOrderRq);
            }
            if (!matchResult.trades().isEmpty()) {
                applyExecutionEffects(security, enterOrderRq, matchResult);
            }
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void applyActivationEffects(EnterOrderRq rq){
        rq.setStopPriceZero();
        eventPublisher.publish(new OrderActivatedEvent(rq.getRequestId(), rq.getOrderId()));
    }

    private void applyExecutionEffects(Security security, EnterOrderRq rq, MatchResult matchResult){
        rq.setStopPriceZero();
        applyExecutionUpdates(security, rq.getOrderId(), rq.getRequestId(),  matchResult);
        executeEnabledOrders(rq);
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        if (enterOrderRq.getMinimumExecutionQuantity() < 0)
            errors.add(Message.ORDER_MINIMUM_EXEC_QUANTITY_NEGATIVE);
        if (enterOrderRq.getMinimumExecutionQuantity() > enterOrderRq.getQuantity())
            errors.add(Message.ORDER_MINIMUM_EXEC_QUANTITY_BIGGER_THAN_QUANTITY);

        if (enterOrderRq.isStopLimitOrderRq())
            validateEnterStopOrder(enterOrderRq, errors);
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (enterOrderRq.hasMinimumExecutionQuantity() && security.isAuction())
            errors.add(Message.CANNOT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE);
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }
        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateEnterStopOrder(EnterOrderRq enterOrderRq, List<String> errors) throws InvalidRequestException {
        if (enterOrderRq.getStopPrice() < 0)
            errors.add(Message.STOP_PRICE_CANNOT_BE_NEGATIVE);
        if (enterOrderRq.hasMinimumExecutionQuantity())
            errors.add(Message.STOP_ORDER_CANNOT_HAVE_MINIMUM_EXEC_QUANTITY);
        if (enterOrderRq.isIcebergOrderRq())
            errors.add(Message.STOP_ORDER_CANNOT_BE_ICEBERG_TOO);
        if (securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin()).isAuction())
            errors.add(Message.CANNOT_ADD_STOP_ORDER_IN_AUCTION_STATE);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin()) == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void executeEnabledOrders(EnterOrderRq rq){
        execBuyAndSell(rq, Side.BUY);
        execBuyAndSell(rq, Side.SELL);
    }

    private boolean isEnabledOver(Side side, Security security){
        return(
                (side == Side.BUY && (security.getBuyEnabledOrders().theSize() == 0))
                ||
                (side == Side.SELL && (security.getSellEnabledOrders().theSize() == 0))
                );
    }

    private void executeTheEnabled(Order order, long reqId, Side side){
        Security security = order.getSecurity();

        security.removeEnabledOrder(reqId, side);
        eventPublisher.publish(new OrderActivatedEvent(reqId, order.getOrderId()));
        order.setStopPriceZero();

        MatchResult matchResult = security.handleEnterOrder(order, reqId, matcher);
        if (!matchResult.trades().isEmpty())
            applyExecutionUpdates(security, order.getOrderId(), reqId, matchResult);
    }

    private void execBuyAndSell(EnterOrderRq enterRq, Side side){
        Security security = securityRepository.findSecurityByIsin(enterRq.getSecurityIsin());
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

    private void applyExecutionUpdates(Security security, long orderId, long reqId, MatchResult matchResult){

        eventPublisher.publish(new OrderExecutedEvent(reqId, orderId,
                matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
        
        security.updateLastTradePrice(matchResult.trades().getLast().getPrice());
        security.handleDisabledOrders();
    }

}
