package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class Matcher {
    private MatchingState securityState;
    public MatchResult match(Order newOrder) {
        int prevQuantity = newOrder.getQuantity();
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyersCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());
                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueue(icebergOrder);
                }
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
        }
        if (newOrder.isFirstEntry() && !newOrder.isMinExecQuantitySatisfied(prevQuantity)) {
            rollbackTrades(newOrder, trades);
            return MatchResult.notSatisfyMinExec();
        }
        return MatchResult.executed(newOrder, trades);
    }

    public MatchResult auctionMatch(OrderBook candidateOrderBook, int openingPrice) {
        MatchResult result;
        LinkedList<Trade> trades = new LinkedList<>();
        LinkedList<Order> buyQueue = candidateOrderBook.getBuyQueue();
        LinkedList<Order> sellQueue = candidateOrderBook.getSellQueue();
        for (var buyOrder : buyQueue) {

            while (!sellQueue.isEmpty() && buyOrder.getQuantity() > 0) {
                Order matchingSellOrder = sellQueue.getFirst();

                Trade trade = new Trade(buyOrder.getSecurity(), openingPrice, Math.min(buyOrder.getQuantity(),
                        matchingSellOrder.getQuantity()), buyOrder, matchingSellOrder);

                // Added by me. TODO: Is there increase in path? I don't think so.
                buyOrder.getBroker().increaseCreditBy(buyOrder.getValue());
                trade.decreaseBuyersCredit();
                trade.increaseSellersCredit();
                trades.add(trade);

                if (buyOrder.getQuantity() > matchingSellOrder.getQuantity()) {
                    buyOrder.decreaseQuantity(matchingSellOrder.getQuantity());
                    buyOrder.getBroker().decreaseCreditBy(buyOrder.getValue());
                    if (buyOrder instanceof IcebergOrder icebergOrder) {
                        icebergOrder.replenish();
                    }
                    matchingSellOrder.makeQuantityZero();
                    // TODO:: handle if sell order is iceberg
                } else if (buyOrder.getQuantity() == matchingSellOrder.getQuantity()) {
                    buyOrder.makeQuantityZero();
                    matchingSellOrder.makeQuantityZero();
                    // TODO:: handle if sell and buy order is iceberg
                } else { // buyOrder.getQuantity() < matchingSellOrder.getQuantity()
                    matchingSellOrder.decreaseQuantity(buyOrder.getQuantity());
                    if (matchingSellOrder instanceof IcebergOrder icebergOrder) {
                        icebergOrder.replenish(); //TODO what if the matchingorder is an iceberg order?
                    }
                    buyOrder.makeQuantityZero();
                    // TODO:: handle if buy order is iceberg

                }
            }
        }

        result = MatchResult.auctioned(trades);
        return result;
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
        if (this.securityState == MatchingState.AUCTION) {
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
        result = match(order);

        order.unmarkFirstEntry();
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT || result.outcome() == MatchingOutcome.NOT_SATISFY_MIN_EXEC)
            return result;

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
        return result;
    }

    public MatchResult execute(Order order, int lastTradePrice, MatchingState securityState){
        this.securityState = securityState;
        MatchResult result;
        if(order.isStopLimitOrder()) {
            result = recognizeOutcome(order, lastTradePrice);
            if(result.outcome() != MatchingOutcome.ACTIVATED)
                return(result);
            order.setStopPriceZero();
        }
        return(execute(order));
    }

}
