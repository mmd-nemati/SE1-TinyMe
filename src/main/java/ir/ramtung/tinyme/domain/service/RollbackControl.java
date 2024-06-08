package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.Trade;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.ListIterator;

@Component
@Scope("prototype")
public class RollbackControl {
    public MatchResult rollbackIfNecessary(Order newOrder, LinkedList<Trade> trades, Order checkOrder) {
        return rollbackIfNecessary(newOrder, trades, checkOrder, checkOrder.getValue());
    }

    public MatchResult rollbackIfNecessary(Order newOrder, LinkedList<Trade> trades, Order checkOrder, long value) {
        if (needsRollback(checkOrder, value)) {
            rollbackTrades(newOrder, trades);
            return MatchResult.notEnoughCredit();
        }

        return null;
    }

    public MatchResult rollbackMinExecIfNecessary(Order newOrder, LinkedList<Trade> trades, int prevQuantity) {
        if (!newOrder.isMinExecQuantitySatisfied(prevQuantity)) {
            rollbackTrades(newOrder, trades);
            return MatchResult.notEnoughCredit();
        }

        return null;
    }

    private boolean needsRollback(Order checkOrder, long value) {
        return !checkOrder.getBroker().hasEnoughCredit(value);
    }

    public void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
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
}
