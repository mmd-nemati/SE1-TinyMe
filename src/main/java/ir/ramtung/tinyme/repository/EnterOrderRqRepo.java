package ir.ramtung.tinyme.repository;

import ir.ramtung.tinyme.domain.entity.Order;
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
    private boolean isRightPlace(EnterOrderRq inHashRq, EnterOrderRq newRq){
        return(
                (ascendingStore && (inHashRq.getStopPrice()
                        > newRq.getStopPrice()))
                        ||
                        (!ascendingStore && (inHashRq.getStopPrice()
                                < newRq.getStopPrice()))
        );
    }
    public void addOrderRq(EnterOrderRq newRq) {
        if(orderById.isEmpty())
            orderById.put(newRq.getOrderId(), newRq);
        else{
            for(EnterOrderRq inHashOrder : orderById.values()){
                if(isRightPlace(inHashOrder, newRq))
                    break;
            }
        }
        orderById.put(newRq.getOrderId(), newRq);
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
