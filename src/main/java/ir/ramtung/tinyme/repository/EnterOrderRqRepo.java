package ir.ramtung.tinyme.repository;

import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@Component
public class EnterOrderRqRepo {
    private final HashMap<String, EnterOrderRq> orderById = new HashMap<>();
    public EnterOrderRq findOrderRqById(String id) {
        return orderById.get(id);
    }
    public void addOrderRq(EnterOrderRq orderRq) {orderById.put(orderRq.getOrderId(), orderRq);}
    public void clear() {
        orderById.clear();
    }
    public void removeById(String id) { orderById.remove(id); }
    public boolean exist(String id) { return(orderById.containsKey(id)); }
    Iterable<? extends EnterOrderRq> allOrders() { return orderById.values(); }
}
