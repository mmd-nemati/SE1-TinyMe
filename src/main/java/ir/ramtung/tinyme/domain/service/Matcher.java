package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Controls.RollbackControl;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedList;

@Service
public class Matcher {
    private MatchingState securityState = MatchingState.CONTINUOUS;
    private int openingPrice;
    private OrderBook orderBook;
    @Autowired
    private RollbackControl rollbackControl;
    public MatchResult match(Order newOrder) {
        int prevQuantity = newOrder.getQuantity();
        if (securityState == MatchingState.CONTINUOUS)
            this.orderBook = newOrder.getSecurity().getOrderBook();

        LinkedList<Trade> trades = new LinkedList<>();

        while (canMatch(newOrder)) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;
            int tradePrice = (this.securityState == MatchingState.AUCTION) ? openingPrice : matchingOrder.getPrice();
            Trade trade = new Trade(newOrder.getSecurity(), tradePrice, Math.min(newOrder.getQuantity(),
                    matchingOrder.getQuantity()), newOrder, matchingOrder);

            if (newOrder.getSide() == Side.BUY && this.securityState == MatchingState.CONTINUOUS) {
                if (rollbackControl.rollbackIfNecessary(newOrder, trades, trade.getBuy(), trade.getTradedValue()) != null)
                    return MatchResult.notEnoughCredit();
                trade.decreaseBuyersCredit();
            }
            trade.increaseSellersCredit();
            trades.add(trade);
            handleQuantities(newOrder, matchingOrder);
        }
        if (rollbackControl.rollbackMinExecIfNecessary(newOrder, trades, prevQuantity) != null)
            return MatchResult.notSatisfyMinExec();

        return MatchResult.executed(newOrder, trades);
    }

    public MatchResult auctionMatch(OrderBook candidateOrderBook, int openingPrice) {
        LinkedList<Trade> trades = new LinkedList<>();
        this.openingPrice = openingPrice;
        this.orderBook = candidateOrderBook;
        for (var buyOrder : candidateOrderBook.getBuyQueue())
            trades.addAll(execute(buyOrder).trades());

        candidateOrderBook.removeZeroQuantityOrders();

        return MatchResult.auctioned(trades);
    }

    public void handleQuantities(Order newOrder, Order matchingOrder) {
        if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
            newOrder.decreaseQuantity(matchingOrder.getQuantity());
            orderBook.removeFirst(matchingOrder.getSide());
            if (matchingOrder instanceof IcebergOrder icebergOrder) {
                icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                icebergOrder.replenish();
                if (icebergOrder.getQuantity() > 0)
                    orderBook.enqueue(icebergOrder);
            }
        }
        else {
            matchingOrder.decreaseQuantity(newOrder.getQuantity());
            newOrder.makeQuantityZero();
        }
    }

    public boolean canMatch(Order order) {
        return orderBook.hasOrderOfType(order.getSide().opposite()) && order.getQuantity() > 0;
    }

    private MatchResult recognizeOutcome(Order order, int lastTradePrice){
        if (order.getSide() == Side.BUY && !order.getBroker().hasEnoughCredit(order.getValue()))
            return MatchResult.notEnoughCredit();

        if (order.getSide() == Side.BUY && order.getStopPrice() > lastTradePrice)
            return MatchResult.accepted();

        if (order.getSide() == Side.SELL && order.getStopPrice() < lastTradePrice)
            return MatchResult.accepted();

        return MatchResult.activated();
    }

    public MatchResult execute(Order order) {
        MatchResult result = match(order);

        order.markAsNew();
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT || result.outcome() == MatchingOutcome.NOT_SATISFY_MIN_EXEC)
            return result;

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY && this.securityState == MatchingState.CONTINUOUS) {
                if (rollbackControl.rollbackIfNecessary(order, result.trades(), order) != null)
                    return MatchResult.notEnoughCredit();
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        for (Trade trade : result.trades()) {
            trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
            trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
        }
        return result;
    }

    public MatchResult execute(Order order, int lastTradePrice, MatchingState securityState){
        this.securityState = securityState;
        if (securityState == MatchingState.AUCTION) {
            if (order.getQuantity() > 0) {
                if (order.getSide() == Side.BUY) {
                    if (!order.getBroker().hasEnoughCredit(order.getValue()))
                        return MatchResult.notEnoughCredit();
                    order.getBroker().decreaseCreditBy(order.getValue());
                }
                order.getSecurity().getOrderBook().enqueue(order);
            }
            return MatchResult.executed(order, new LinkedList<>());
        }

        if (order.isStopLimitOrder()) {
            MatchResult result = recognizeOutcome(order, lastTradePrice);
            if(result.outcome() != MatchingOutcome.ACTIVATED)
                return(result);
            order.setStopPriceZero();
        }
        return execute(order);
    }
}
