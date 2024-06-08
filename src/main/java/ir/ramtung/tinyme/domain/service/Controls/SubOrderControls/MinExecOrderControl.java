package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;

import java.util.List;

public class MinExecOrderControl extends CommonOrderControl{

    public MinExecOrderControl(SecurityRepository securityRepository, BrokerRepository brokerRepository,
                            ShareholderRepository shareholderRepository){
        super(securityRepository, brokerRepository, shareholderRepository);
    }
    public List<String> generateErrors(EnterOrderRq enterOrderRq) {
        List<String> errors = super.generateErrors(enterOrderRq);

        if (enterOrderRq.getMinimumExecutionQuantity() < 0)
            errors.add(Message.ORDER_MINIMUM_EXEC_QUANTITY_NEGATIVE);
        if (enterOrderRq.getMinimumExecutionQuantity() > enterOrderRq.getQuantity())
            errors.add(Message.ORDER_MINIMUM_EXEC_QUANTITY_BIGGER_THAN_QUANTITY);

        return errors;
    }
}
