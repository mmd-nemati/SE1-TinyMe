package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.Broker;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Shareholder;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@Component
@Scope("prototype")
public class OrderControl {
    private SecurityRepository securityRepository;
    private BrokerRepository brokerRepository;
    private ShareholderRepository shareholderRepository;

    private CommonOrderControl commonOrderControl;
    private MinExecOrderControl minExecOrderControl;
    private StopOrderControl stopOrderControl;

    public OrderControl(SecurityRepository securityRepository, BrokerRepository brokerRepository,
                        ShareholderRepository shareholderRepository){
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        commonOrderControl = new CommonOrderControl(securityRepository, brokerRepository, shareholderRepository);
        minExecOrderControl = new MinExecOrderControl(securityRepository, brokerRepository, shareholderRepository);
        stopOrderControl = new StopOrderControl(securityRepository, brokerRepository, shareholderRepository);
    }

    public void generateErrors(EnterOrderRq enterOrderRq) throws InvalidRequestException{
        List<String> errors = new LinkedList<>();

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
