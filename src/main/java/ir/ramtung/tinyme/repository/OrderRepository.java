package ir.ramtung.tinyme.repository;

import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@Component
public class OrderRepository {
    private final HashMap<String, Order> orderById = new HashMap<>();
    public Order findOrderById(String id) {
        return orderById.get(id);
    }
    public void addOrder(Order order) {orderById.put(order.getOrderId(), order);}
    public void clear() {
        orderById.clear();
    }
    public void removeById(String id) { orderById.remove(id); }
    public boolean exist(String id) { return(orderById.containsKey(id)); }
    Iterable<? extends Order> allOrders() { return orderById.values(); }
}
