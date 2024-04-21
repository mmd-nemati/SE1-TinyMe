package ir.ramtung.tinyme.repository;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@Component
public class EnterOrderRqRepo {
    private final HashMap<Long, EnterOrderRq> orderById = new HashMap<>();
    public EnterOrderRq findOrderRqById(Long id) {
        return orderById.get(id);
    }
    public void addOrderRq(EnterOrderRq orderRq) {orderById.put(orderRq.getOrderId(), orderRq);}
    public void clear() {
        orderById.clear();
    }
    public void removeById(Long id) { orderById.remove(id); }
    public boolean exist(Long id) { return(orderById.containsKey(id)); }
    public Iterable<? extends EnterOrderRq> allOrderRqs() { return orderById.values(); }
}
