package ir.ramtung.tinyme.repository;

import ir.ramtung.tinyme.domain.entity.Order;

import java.util.HashMap;

import java.util.*;
public class EnterOrderRepo {
    private final HashMap<Long, Order> orderById;
    boolean ascendingStore;

    public EnterOrderRepo(boolean ascendingStore) {
        orderById = new HashMap<>();
        this.ascendingStore = ascendingStore;
    }

    public Order findByRqId(long rqId) {
        return orderById.get(rqId);
    }

    public Order findByOrderId(long orderId) {
        return orderById.values().stream()
                .filter(order -> order.getOrderId() == orderId)
                .findFirst()
                .orElse(null);
    }

    private boolean isRightPlace(Order inHashRq, Order newRq){
        return(
                (ascendingStore && (inHashRq.getStopPrice()
                        > newRq.getStopPrice()))
                        ||
                        (!ascendingStore && (inHashRq.getStopPrice()
                                < newRq.getStopPrice()))
        );
    }

    public void addOrder(Order newRq, long reqId) {
        if(orderById.isEmpty())
            orderById.put(reqId, newRq);
        else{
            for(Order inHashOrder : orderById.values()){
                if(isRightPlace(inHashOrder, newRq))
                    break;
            }
        }
        orderById.put(reqId, newRq);
    }


    public void removeByRqId(long rqId) {
        if (existByRqId(rqId))
            orderById.remove(rqId);
    }

    public void removeByOrderId(long orderId) {
        orderById.entrySet().removeIf(entry -> entry.getValue().getOrderId() == orderId);
    }

    public boolean existByRqId(long rqId) { return(orderById.containsKey(rqId)); }

    public boolean existByOrderId(long orderId) {
        return orderById.values().stream().anyMatch(order -> order.getOrderId() == orderId);
    }

    public int theSize(){ return( orderById.size()); }

    public EnterOrderRepo makeCopy(){
        EnterOrderRepo cloned = new EnterOrderRepo(ascendingStore);
        for(long currentKey : orderById.keySet())
            cloned.addOrder(orderById.get(currentKey), currentKey);

        return(cloned);
    }

    public Iterable<? extends Long> allOrderKeysSortedByStopPrice() {
        List<Long> sortedKeys = new ArrayList<>(orderById.keySet());
        Comparator<Long> comparator = Comparator.comparing(orderId -> orderById.get(orderId).getStopPrice());

        if (!ascendingStore)
            comparator = comparator.reversed();

        sortedKeys.sort(comparator);
        return sortedKeys;
    }

}
