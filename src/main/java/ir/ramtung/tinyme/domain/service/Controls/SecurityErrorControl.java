package ir.ramtung.tinyme.domain.service.Controls;

import ir.ramtung.tinyme.domain.entity.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Setter
@Getter
@Component
@Scope("prototype")
public class SecurityErrorControl {
    public void verifyNewOrder(Order order, boolean isAuction) throws InvalidRequestException {
        if (order.isStopLimitOrder() && isAuction)
            throw new InvalidRequestException(Message.CANNOT_ADD_STOP_ORDER_IN_AUCTION_STATE);
    }
    public void verifyDelete(Order order, boolean isAuction) throws InvalidRequestException {
        if (order.isStopLimitOrder() && isAuction)
            throw new InvalidRequestException(Message.CANNOT_DELETE_STOP_ORDER_IN_AUCTION_STATE);
    }
    public void verifyUpdate(Order order, EnterOrderRq updateOrderRq) throws InvalidRequestException {
        if ((order instanceof IcebergOrder) && !updateOrderRq.isIcebergOrderRq())
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.isIcebergOrderRq())
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order.getMinimumExecutionQuantity() != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY);
        if (!order.isStopLimitOrder() && updateOrderRq.isStopLimitOrderRq())
            throw new InvalidRequestException(Message.CANNOT_CHANGE_STOP_PRICE_FOR_ACTIVATED);

        verifyStopPrice(order, updateOrderRq);
    }

    private void verifyStopPrice(Order order, EnterOrderRq updateOrderRq) throws InvalidRequestException {
        if (order.isStopLimitOrder()) {
            if (updateOrderRq.isIcebergOrderRq())
                throw new InvalidRequestException(Message.STOP_ORDER_CANNOT_BE_ICEBERG_TOO);
            if (updateOrderRq.hasMinimumExecutionQuantity())
                throw new InvalidRequestException(Message.STOP_ORDER_CANNOT_HAVE_MINIMUM_EXEC_QUANTITY);
            if (!order.isUpdatingStopOrderPossible(updateOrderRq.getOrderId(), updateOrderRq.getSecurityIsin(), updateOrderRq.getBrokerId(), updateOrderRq.getSide(), updateOrderRq.getShareholderId()))
                throw new InvalidRequestException(Message.CANNOT_CHANGE_NOT_ALLOWED_PARAMETERS_BEFORE_ACTIVATION);
            if (order.getSecurity().isAuction())
                throw new InvalidRequestException(Message.CANNOT_UPDATE_STOP_ORDER_IN_AUCTION_STATE);
        }
    }
}
