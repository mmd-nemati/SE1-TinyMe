package ir.ramtung.tinyme.repository;

import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;

import java.util.HashMap;

public class EnterOrderRepo {
    private final HashMap<Long, Order> orderById;
    boolean ascendingStore;
    public EnterOrderRepo(boolean ascendingStore) {
        orderById = new HashMap<>();
        this.ascendingStore = ascendingStore;
    }

    public Order findOrderById(Long id) {
        return orderById.get(id);
    }
    public void addOrder(Order order) {
        if(orderById.isEmpty())
            orderById.put(order.getOrderId(), order);
        else{
            for(Order or1 : orderById.values()){
                if(ascendingStore && (or1.getStopPrice() > order.getStopPrice())){
                    orderById.put(order.getOrderId(), order);
                    return;
                }
                if(!ascendingStore && (or1.getStopPrice() < order.getStopPrice())){
                    orderById.put(order.getOrderId(), order);
                    return;
                }
            }
        }
        orderById.put(order.getOrderId(), order);
    }
    public void clear() {
        orderById.clear();
    }
    public void removeById(Long id) { if(exist(id))orderById.remove(id); }
    public boolean exist(Long id) { return(orderById.containsKey(id)); }
    public Iterable<? extends Order> allOrderRqs() { return orderById.values(); }
}
