package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import ir.ramtung.tinyme.repository.*;
import org.jgroups.util.Tuple;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;

@Service
public class OrderHandler extends Handler{

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        super(securityRepository, brokerRepository, shareholderRepository, eventPublisher, matcher);
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
            else {
                matchResult = security.updateOrder(enterOrderRq, matcher);
                if (matchResult.outcome() == MatchingOutcome.ACCEPTED)
                    security.updateDisabledOrders(enterOrderRq);
            }

            if (publishRejectedOutcome(matchResult, enterOrderRq))
                return;
            publishSuccessfulEntry(enterOrderRq);
            publishOpeningPriceEvent(security);

            if (matchResult.outcome() == MatchingOutcome.EXECUTED && enterOrderRq.isStopLimitOrderRq())
                applyActivationEffects(enterOrderRq, security);

            applyExecutionEffects(security, enterOrderRq, matchResult);
        }
        catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            publishOpeningPriceEvent(security);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void removeReqFromDisables(EnterOrderRq enterOrderRq, Security security){
        EnterOrderRepo orders;
        if(enterOrderRq.getSide() == Side.BUY)
            orders = security.getQueueInfo().getBuyDisabledOrders();
        else
            orders = security.getQueueInfo().getSellDisabledOrders();

        orders.removeByOrderId(enterOrderRq.getOrderId());
    }

    private void applyActivationEffects(EnterOrderRq rq, Security security){
        rq.setStopPriceZero();
        if(rq.getRequestType() == OrderEntryType.UPDATE_ORDER)
            removeReqFromDisables(rq, security);
        eventPublisher.publish(new OrderActivatedEvent(rq.getRequestId(), rq.getOrderId()));
    }

    private void applyExecutionEffects(Security security, EnterOrderRq rq, MatchResult matchResult){
        if (matchResult.trades().isEmpty())
            return;
        rq.setStopPriceZero();
        applyExecutionUpdates(security, rq.getOrderId(), rq.getRequestId(),  matchResult);
        executeEnabledOrders(security);
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

        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.hasMinimumExecutionQuantity() && security.isAuction())
                errors.add(Message.CANNOT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE);
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

    private void publishOpeningPriceEvent(Security security) {
        if (security.isAuction()) {
            Tuple<Integer, Integer> opening = security.calculateOpeningPrice();
            eventPublisher.publish(new OpeningPriceEvent(security.getIsin(), opening.getVal1(), opening.getVal2()));
        }
    }

    private boolean publishRejectedOutcome(MatchResult matchResult, EnterOrderRq enterOrderRq) {
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
            return true;
        }
        else if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
            return true;
        }
        else if (matchResult.outcome() == MatchingOutcome.NOT_SATISFY_MIN_EXEC) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
            return true;
        }

        return false;
    }

    private void publishSuccessfulEntry(EnterOrderRq enterOrderRq) {
        if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
            eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        else
            eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
    }
}
