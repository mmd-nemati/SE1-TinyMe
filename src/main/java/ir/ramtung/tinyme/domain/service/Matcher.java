package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class Matcher {
    private MatchingState securityState;
    private int openingPrice;
    private OrderBook orderBook;
    public MatchResult match(Order newOrder) {
        int prevQuantity = newOrder.getQuantity();
        if (securityState == MatchingState.CONTINUOUS)
            this.orderBook = newOrder.getSecurity().getOrderBook();

        LinkedList<Trade> trades = new LinkedList<>();

        while (canMatch(newOrder)) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;
            int tradePrice;
            if (this.securityState == MatchingState.AUCTION)
                tradePrice = openingPrice;
            else
                tradePrice = matchingOrder.getPrice();
            Trade trade = new Trade(newOrder.getSecurity(), tradePrice, Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY && this.securityState == MatchingState.CONTINUOUS) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyersCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);
            handleQuantities(newOrder, matchingOrder);
        }
        if (newOrder.isFirstEntry() && !newOrder.isMinExecQuantitySatisfied(prevQuantity)) {
            rollbackTrades(newOrder, trades);
            return MatchResult.notSatisfyMinExec();
        }
        return MatchResult.executed(newOrder, trades);
    }

    public MatchResult auctionMatch(OrderBook candidateOrderBook, int openingPrice) {
        LinkedList<Trade> trades = new LinkedList<>();
        this.openingPrice = openingPrice;
        this.orderBook = candidateOrderBook;
        for (var buyOrder : candidateOrderBook.getBuyQueue())
            trades.addAll(execute(buyOrder).trades());

        for (Order order : candidateOrderBook.getBuyQueue())
            if (order.getQuantity() == 0)
                candidateOrderBook.removeByOrderId(Side.BUY, order.getOrderId());

        for (Order order : candidateOrderBook.getSellQueue())
            if (order.getQuantity() == 0)
                candidateOrderBook.removeByOrderId(Side.SELL, order.getOrderId());

        return MatchResult.auctioned(trades);
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if (newOrder.getSide() == Side.BUY) {
            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreSellOrder(it.previous().getSell());
            }
        }
        else if (newOrder.getSide() == Side.SELL) {
            newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getBuy().getBroker().increaseCreditBy(trade.getTradedValue()));

            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreBuyOrder(it.previous().getBuy());
            }
        }
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
        MatchResult result;
        result = match(order);

        order.unmarkFirstEntry();
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT || result.outcome() == MatchingOutcome.NOT_SATISFY_MIN_EXEC)
            return result;

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY && this.securityState == MatchingState.CONTINUOUS) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
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
        MatchResult result;
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
            result = MatchResult.executed(order, new LinkedList<>());
            return result;
        }

        if(order.isStopLimitOrder()) {
            result = recognizeOutcome(order, lastTradePrice);
            if(result.outcome() != MatchingOutcome.ACTIVATED)
                return(result);
            order.setStopPriceZero();
        }
        return(execute(order));
    }
}
