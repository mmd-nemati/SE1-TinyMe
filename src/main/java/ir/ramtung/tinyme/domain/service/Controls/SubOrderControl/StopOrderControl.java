package ir.ramtung.tinyme.domain.service.Controls.SubOrderControl;

import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StopOrderControl extends CommonOrderControl {

    public StopOrderControl(SecurityRepository securityRepository, BrokerRepository brokerRepository,
                            ShareholderRepository shareholderRepository){
        super(securityRepository, brokerRepository, shareholderRepository);
    }
    public List<String> generateErrors(EnterOrderRq enterOrderRq) {
        List<String> errors = super.generateErrors(enterOrderRq);

        if (enterOrderRq.getStopPrice() < 0)
            errors.add(Message.STOP_PRICE_CANNOT_BE_NEGATIVE);
        if (enterOrderRq.hasMinimumExecutionQuantity())
            errors.add(Message.STOP_ORDER_CANNOT_HAVE_MINIMUM_EXEC_QUANTITY);
        if (enterOrderRq.isIcebergOrderRq())
            errors.add(Message.STOP_ORDER_CANNOT_BE_ICEBERG_TOO);

        return errors;
    }
}
