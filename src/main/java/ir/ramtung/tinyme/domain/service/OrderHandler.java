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
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.EnterOrderRqRepo;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
            }
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            else
                eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            if(matchResult.outcome() == MatchingOutcome.EXECUTED && enterOrderRq.getStopPrice() != 0) {
                applyActivationEffects(enterOrderRq);
            }
            if (!matchResult.trades().isEmpty()) {
                applyExecutionEffects(enterOrderRq, matchResult);
            }
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void applyActivationEffects(EnterOrderRq rq){
        rq.setStopPriceZero();
        eventPublisher.publish(new OrderActivatedEvent(rq.getRequestId(), rq.getOrderId()));
    }

    private void applyExecutionEffects(EnterOrderRq rq, MatchResult matchResult){
        rq.setStopPriceZero();
        applyExecuteEffects(rq, matchResult);
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
        if (enterOrderRq.getStopPrice() != 0)
            validateEnterStopOrder(enterOrderRq, errors);
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
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

    private void validateEnterStopOrder(EnterOrderRq enterOrderRq, List<String> errors){
        if(enterOrderRq.getStopPrice() < 0)
            errors.add(Message.STOP_PRICE_NEGATIVE);
        if(enterOrderRq.getMinimumExecutionQuantity() != 0)
            errors.add(Message.STOP_LIMIT_AND_MINIMUM_EXEC_QUANTITY);
        if(enterOrderRq.getPeakSize() != 0)
            errors.add(Message.STOP_ORDER_IS_ICEBERG_TOO);
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
                (side == Side.BUY && (security.getBuyEnabledRqs().theSize() == 0))
                ||
                (side == Side.SELL && (security.getSellEnabledRqs().theSize() == 0))
                );
    }

    private void executeTheEnabled(EnterOrderRq rq, Side side){
        Security security = securityRepository.findSecurityByIsin(rq.getSecurityIsin());
        Broker broker = brokerRepository.findBrokerById(rq.getBrokerId());
        Shareholder shareholder = shareholderRepository.findShareholderById(rq.getShareholderId());

        security.removeEnabledOrder(rq.getOrderId(), side);
        eventPublisher.publish(new OrderActivatedEvent(rq.getRequestId(), rq.getOrderId()));
        rq.setStopPriceZero();

        MatchResult matchResult = security.newOrder(rq, broker, shareholder, matcher);
        if (!matchResult.trades().isEmpty())
            applyExecuteEffects(rq, matchResult);
    }

    private void execBuyAndSell(EnterOrderRq enterRq, Side side){
        Security security = securityRepository.findSecurityByIsin(enterRq.getSecurityIsin());
        EnterOrderRqRepo reqs;
        if(side == Side.BUY)
            reqs = security.getBuyEnabledRqs().makeCopy();
        else
            reqs = security.getBuyEnabledRqs().makeCopy();

        if(reqs != null) {
            for (EnterOrderRq rq : reqs.allOrderRqs())
                executeTheEnabled(rq, side);
        }

        if(isEnabledOver(side, security))
                return;

        execBuyAndSell(enterRq, side);
    }

    private void applyExecuteEffects(EnterOrderRq rq, MatchResult matchResult){
        Security security = securityRepository.findSecurityByIsin(rq.getSecurityIsin());
        eventPublisher.publish(new OrderExecutedEvent(rq.getRequestId(), rq.getOrderId(),
                matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
        security.updateLastTradePrice(matchResult.trades().getLast().getPrice());
        security.handleDisabledOrders();
    }

}
