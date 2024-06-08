package ir.ramtung.tinyme.domain.service.Controls;

import ir.ramtung.tinyme.domain.service.Controls.SubOrderControl.*;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Scope("prototype")
public class OrderErrorControl {
    private final CommonOrderControl commonOrderControl;
    private final MinExecOrderControl minExecOrderControl;
    private final StopOrderControl stopOrderControl;

    public OrderErrorControl(SecurityRepository securityRepository, BrokerRepository brokerRepository,
                             ShareholderRepository shareholderRepository){
        commonOrderControl = new CommonOrderControl(securityRepository, brokerRepository, shareholderRepository);
        minExecOrderControl = new MinExecOrderControl(securityRepository, brokerRepository, shareholderRepository);
        stopOrderControl = new StopOrderControl(securityRepository, brokerRepository, shareholderRepository);
    }

    public void generateErrors(EnterOrderRq enterOrderRq) throws InvalidRequestException{
        List<String> errors;

        if(enterOrderRq.isStopLimitOrderRq())
            errors = stopOrderControl.generateErrors(enterOrderRq);
        else if(enterOrderRq.hasMinimumExecutionQuantity())
            errors = minExecOrderControl.generateErrors(enterOrderRq);
        else
            errors = commonOrderControl.generateErrors(enterOrderRq);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }
}
