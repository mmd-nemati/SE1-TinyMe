package ir.ramtung.tinyme.data;

import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.OrderBook;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.repository.EnterOrderRepo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SecurityQueueInfo {
    private final static boolean ASCENDING = true;
    private final static boolean DESCENDING = false;

    private OrderBook orderBook = new OrderBook();
    private EnterOrderRepo buyDisabledOrders = new EnterOrderRepo(ASCENDING);
    private EnterOrderRepo buyEnabledOrders = new EnterOrderRepo(ASCENDING);
    private EnterOrderRepo sellDisabledOrders = new EnterOrderRepo(DESCENDING);
    private EnterOrderRepo sellEnabledOrders = new EnterOrderRepo(DESCENDING);

    public EnterOrderRepo getDisabledOrders(Side side) {
        return side == Side.BUY ? buyDisabledOrders : sellDisabledOrders;
    }

    public EnterOrderRepo getEnabledOrders(Side side) {
        return side == Side.BUY ? buyEnabledOrders : sellEnabledOrders;
    }

    public void addToDisabled(Order order, long rqId) {
        if(order.getSide() == Side.BUY)
            buyDisabledOrders.addOrder(order, rqId);
        else
            sellDisabledOrders.addOrder(order, rqId);
    }

    public Order findOrder(Side side, long orderId) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(side, orderId);
        if (order != null)
            return order;

        order = buyDisabledOrders.findByOrderId(orderId);
        if (order != null)
            return order;

        order = sellDisabledOrders.findByOrderId(orderId);
        if (order != null)
            return order;

        throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
    }
    public void deleteOrder(Order order, Side deleteOrderSide, long deleteOrderId) {
        if (orderBook.hasByOrderId(order.getSide(), order.getOrderId()))
            orderBook.removeByOrderId(deleteOrderSide, deleteOrderId);
        else if (buyDisabledOrders.existByOrderId(deleteOrderId))
            buyDisabledOrders.removeByOrderId(deleteOrderId);
        else if (sellDisabledOrders.existByOrderId(deleteOrderId))
            sellDisabledOrders.removeByOrderId(deleteOrderId);
    }

    public void deleteEnabledOrder(long rqId, Side side) {
        if (side == Side.BUY)
            buyEnabledOrders.removeByRqId(rqId);
        else
            sellEnabledOrders.removeByRqId(rqId);
    }

    public OrderBook getCandidateOrders(int openingPrice){
        OrderBook candidateOrders = new OrderBook();
        for (Order order : orderBook.getBuyQueue())
            if (order.getPrice() >= openingPrice)
                candidateOrders.enqueue(order);

        for (Order order : orderBook.getSellQueue())
            if (order.getPrice() <= openingPrice)
                candidateOrders.enqueue(order);

        return candidateOrders;
    }

    public void syncRemovedOrders(OrderBook candidateOrders, OrderBook candidateOrdersCopy, Side side) {
        for (Order order : candidateOrdersCopy.getQueue(side))
            if (!candidateOrders.hasByOrderId(side, order.getOrderId()))
                orderBook.removeByOrderId(side, order.getOrderId());
    }
}
