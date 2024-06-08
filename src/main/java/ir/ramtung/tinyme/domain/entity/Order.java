package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.Objects;

@SuperBuilder
@EqualsAndHashCode
@ToString
@Getter
public class Order {
    protected long orderId;
    protected Security security;
    protected Side side;
    protected int quantity;
    protected int price;
    protected Broker broker;
    protected Shareholder shareholder;
    @Builder.Default
    protected LocalDateTime entryTime = LocalDateTime.now();
    @Builder.Default
    protected OrderStatus status = OrderStatus.NEW;
    @Builder.Default
    protected int minimumExecutionQuantity = 0;

    @Builder.Default
    protected int stopPrice = 0;

    public Order() {}
    public Order snapshot() {
        return Order.builder().orderId(orderId).security(security).side(side).quantity(quantity).price(price).broker(broker).shareholder(shareholder).entryTime(entryTime).status(OrderStatus.SNAPSHOT).minimumExecutionQuantity(minimumExecutionQuantity).build();
    }

    public Order snapshotWithQuantity(int newQuantity) {
        return Order.builder().orderId(orderId).security(security).side(side).quantity(newQuantity).price(price).broker(broker).shareholder(shareholder).entryTime(entryTime).status(OrderStatus.SNAPSHOT).minimumExecutionQuantity(minimumExecutionQuantity).build();
    }

    public boolean matches(Order other) {
        if (side == Side.BUY)
            return price >= other.price;
        else
            return price <= other.price;
    }

    public void decreaseQuantity(int amount) {
        if (amount > quantity)
            throw new IllegalArgumentException();
        quantity -= amount;
    }

    public void makeQuantityZero() {
        quantity = 0;
    }

    public boolean queuesBefore(Order order) {
        if (order.getSide() == Side.BUY) {
            return price > order.getPrice();
        } else {
            return price < order.getPrice();
        }
    }

    public void queue() {
        status = OrderStatus.QUEUED;
    }

    public void markAsNew(){
        status = OrderStatus.NEW;
    }
    public boolean isQuantityIncreased(int newQuantity) {
        return newQuantity > quantity;
    }

    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        quantity = updateOrderRq.getQuantity();
        price = updateOrderRq.getPrice();
        stopPrice = updateOrderRq.getStopPrice();
    }

    public long getValue() {
        return (long)price * quantity;
    }

    public int getTotalQuantity() { return quantity; }

    public boolean isMinExecQuantitySatisfied(int prevQuantity) {
        return status != OrderStatus.FirstEntry || (prevQuantity - quantity >= minimumExecutionQuantity);
    }

    public void setStopPriceZero() { stopPrice = 0; }

    public boolean isUpdatingStopOrderPossible(long orderId, String Isin, long brokerId, Side side,
                                                   long shareholderId) {
        return  orderId == this.orderId &&
                Objects.equals(Isin, this.security.getIsin()) &&
                side == this.side &&
                shareholderId == this.shareholder.getShareholderId() &&
                brokerId == this.broker.getBrokerId();
    }

    public boolean isStopLimitOrder() { return stopPrice != 0; }
}

