package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;

    public OrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
    }

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        order.queue();
        it.add(order);
    }

    public LinkedList<Order> getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }

    public Order findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean hasByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return true;
        }
        return false;
    }

    public boolean removeByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public void removeZeroQuantityOrders() {
        buyQueue.removeIf(order -> order.getQuantity() == 0);
        sellQueue.removeIf(order -> order.getQuantity() == 0);
    }

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite());
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide());
        order.queue();
        queue.addFirst(order);
    }

    public void restoreSellOrder(Order sellOrder) {
        removeByOrderId(Side.SELL, sellOrder.getOrderId());
        putBack(sellOrder);
    }

    public void restoreBuyOrder(Order buyOrder) {
        removeByOrderId(Side.BUY, buyOrder.getOrderId());
        putBack(buyOrder);
    }

    public boolean hasOrderOfType(Side side) {
        return !getQueue(side).isEmpty();
    }

    public void removeFirst(Side side) {
        getQueue(side).removeFirst();
    }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public int totalBuyQuantityByPrice(int price) {
        return buyQueue.stream()
                .filter(order -> order.getPrice() >= price)
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public int totalSellQuantityByPrice(int price) {
        return sellQueue.stream()
                .filter(order -> order.getPrice() <= price)
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public OrderBook snapshot() {
        OrderBook copy = new OrderBook();
        for (Order order : buyQueue)
            copy.enqueue(order.snapshot());

        for (Order order : sellQueue)
            copy.enqueue(order.snapshot());

        return copy;
    }
}
