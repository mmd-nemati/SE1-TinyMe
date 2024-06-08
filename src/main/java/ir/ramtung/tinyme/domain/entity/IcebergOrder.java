package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class IcebergOrder extends Order {
    protected int peakSize;
    protected int displayedQuantity;
    @Override
    public Order snapshot() {
        return IcebergOrder.builder().orderId(orderId).security(security).side(side).quantity(quantity).price(price).broker(broker).shareholder(shareholder).entryTime(entryTime).peakSize(peakSize).displayedQuantity(displayedQuantity).status(OrderStatus.SNAPSHOT).minimumExecutionQuantity(minimumExecutionQuantity).build();
    }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return IcebergOrder.builder().orderId(orderId).security(security).side(side).quantity(newQuantity).price(price).broker(broker).shareholder(shareholder).entryTime(entryTime).peakSize(peakSize).displayedQuantity(Math.min(newQuantity, peakSize)).status(OrderStatus.SNAPSHOT).minimumExecutionQuantity(minimumExecutionQuantity).build();
    }

    @Override
    public int getQuantity() {
        if (status == OrderStatus.NEW || status == OrderStatus.FirstEntry)
            return super.getQuantity();
        return displayedQuantity;
    }

    @Override
    public void decreaseQuantity(int amount) {
        if (status == OrderStatus.NEW || status == OrderStatus.FirstEntry) {
            super.decreaseQuantity(amount);
            return;
        }
        if (amount > displayedQuantity)
            throw new IllegalArgumentException();
        quantity -= amount;
        displayedQuantity -= amount;
    }

    public void replenish() {
        displayedQuantity = Math.min(quantity, peakSize);
    }

    @Override
    public void queue() {
        displayedQuantity = Math.min(quantity, peakSize);
        super.queue();
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        if (peakSize < updateOrderRq.getPeakSize()) {
            displayedQuantity = Math.min(quantity, updateOrderRq.getPeakSize());
        }
        else if (peakSize > updateOrderRq.getPeakSize()) {
            displayedQuantity = Math.min(displayedQuantity, updateOrderRq.getPeakSize());
        }
        peakSize = updateOrderRq.getPeakSize();
    }
}
