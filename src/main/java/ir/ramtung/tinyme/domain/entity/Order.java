package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

@Builder
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
    @Builder.Default
    protected boolean isFirstEntry = true;

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int minimumExecutionQuantity, boolean isFirstEntry) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.isFirstEntry = isFirstEntry;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int minimumExecutionQuantity) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.isFirstEntry = true;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int minimumExecutionQuantity, int stopPrice, boolean isFirstEntry) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.stopPrice = stopPrice;
        this.isFirstEntry = isFirstEntry;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int minimumExecutionQuantity, int stopPrice) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.stopPrice = stopPrice;
        this.isFirstEntry = true;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.isFirstEntry = true;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = OrderStatus.NEW;
        this.isFirstEntry = true;
    }


    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder) {
        this(orderId, security, side, quantity, price, broker, shareholder, LocalDateTime.now());
    }

    public Order snapshot() {
        return new Order(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.SNAPSHOT, minimumExecutionQuantity);
    }

    public Order snapshotWithQuantity(int newQuantity) {
        return new Order(orderId, security, side, newQuantity, price, broker, shareholder, entryTime, OrderStatus.SNAPSHOT, minimumExecutionQuantity);
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
    }

    public long getValue() {
        return (long)price * quantity;
    }

    public int getTotalQuantity() { return quantity; }

    public boolean isMinExecQuantitySatisfied(int prevQuantity) {
        return minimumExecutionQuantity == 0 || (prevQuantity - quantity >= minimumExecutionQuantity);
    }

    public void unmarkFirstEntry() { isFirstEntry = false; }

    public void setStopPriceZero() { stopPrice = 0; }

    public boolean isAllowedToUpdateStopLimitOrder(Order O) {
        return O.orderId == this.orderId &&
                Objects.equals(O.security.getIsin(), this.security.getIsin()) &&
                O.side == this.side &&
                O.quantity == this.quantity &&
                O.price == this.price &&
                O.shareholder.getShareholderId() == this.shareholder.getShareholderId() &&
                O.entryTime == this.entryTime &&
                O.status == this.status &&
                O.minimumExecutionQuantity == this.minimumExecutionQuantity &&
                O.stopPrice == this.stopPrice &&
                O.isFirstEntry == this.isFirstEntry;
    }
}

