package ir.ramtung.tinyme.repository;

import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@Component
public class EnterOrderRepository {
    private final HashMap<String, EnterOrderRq> orderById = new HashMap<>();
    public EnterOrderRq findOrderById(String id) {
        return orderById.get(id);
    }
    public void addOrder(EnterOrderRq order) {orderById.put(order.getOrderId(), order);}
    public void clear() {
        orderById.clear();
    }
    public void removeById(String id) { orderById.remove(id); }
    Iterable<? extends EnterOrderRq> allSecurities() { return orderById.values(); }
}
