package ir.ramtung.tinyme.repository;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedList;

public class EnterOrderRqRepo {
    private final HashMap<Long, EnterOrderRq> orderById;
    boolean ascendingStore;
    public EnterOrderRqRepo(boolean ascendingStore) {
        orderById = new HashMap<>();
        this.ascendingStore = ascendingStore;
    }

    public EnterOrderRq findOrderRqById(Long id) {
        return orderById.get(id);
    }
    public void addOrderRq(EnterOrderRq orderRq) {
        if(orderById.isEmpty())
            orderById.put(orderRq.getOrderId(), orderRq);
        else{
            for(EnterOrderRq rq : orderById.values()){
                if(ascendingStore && (rq.getStopPrice() > orderRq.getStopPrice())){
                    orderById.put(orderRq.getOrderId(), orderRq);
                    return;
                }
                if(!ascendingStore && (rq.getStopPrice() < orderRq.getStopPrice())){
                    orderById.put(orderRq.getOrderId(), orderRq);
                    return;
                }
            }
        }
        orderById.put(orderRq.getOrderId(), orderRq);
    }
    public void clear() {
        orderById.clear();
    }
    public void removeById(Long id) { if(exist(id)) orderById.remove(id); }
    public boolean exist(Long id) { return(orderById.containsKey(id)); }
    public int theSize(){ return( orderById.size()); };

    public EnterOrderRqRepo makeCopy(){
        EnterOrderRqRepo cloned = new EnterOrderRqRepo(ascendingStore);
        for(EnterOrderRq currentRq : orderById.values())
            cloned.addOrderRq(currentRq);

        return(cloned);
    }
    public Iterable<? extends EnterOrderRq> allOrderRqs() { return orderById.values(); }
}
