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

    public Order findByRqId(Long id) {
        return orderById.get(id);
    }

    public long findKeyByOrderId(Long id) {
        for(long rqId : orderById.keySet()){
            if(orderById.get(rqId).getOrderId() == id)
                return(rqId);
        }
        return(id);
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

    public void clear() { orderById.clear(); }

    public void removeById(Long id) { if(exist(id))orderById.remove(id); }

    public boolean exist(Long id) { return(orderById.containsKey(id)); }

    public int theSize(){ return( orderById.size()); }

    public EnterOrderRepo makeCopy(){
        EnterOrderRepo cloned = new EnterOrderRepo(ascendingStore);
        for(long currentKey : orderById.keySet())
            cloned.addOrder(orderById.get(currentKey), currentKey);

        return(cloned);
    }

    public Iterable<? extends Long> allOrdekeys() { return orderById.keySet(); }
}
