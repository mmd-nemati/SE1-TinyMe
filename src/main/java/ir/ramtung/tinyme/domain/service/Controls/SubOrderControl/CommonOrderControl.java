package ir.ramtung.tinyme.domain.service.Controls.SubOrderControl;

import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import ir.ramtung.tinyme.domain.entity.Security;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;

@Component
@Scope("prototype")
public class CommonOrderControl {
    private final SecurityRepository securityRepository;
    private final BrokerRepository brokerRepository;
    private final ShareholderRepository shareholderRepository;

    public CommonOrderControl(SecurityRepository securityRepository, BrokerRepository brokerRepository,
                              ShareholderRepository shareholderRepository){
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
    }

    public List<String> generateErrors(EnterOrderRq enterOrderRq){
        List<String> errors = addInitialErrors(enterOrderRq);

        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else
            errors.addAll(addSecurityErrors(enterOrderRq, security));


        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);

        return errors;
    }

    public List<String> addInitialErrors (EnterOrderRq enterOrderRq){
        List<String> errors = new LinkedList<>();
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);

        return errors;
    }

    public List<String> addSecurityErrors(EnterOrderRq enterOrderRq, Security security){
        List<String> errors = new LinkedList<>();

        if (enterOrderRq.hasMinimumExecutionQuantity() && security.isAuction())
            errors.add(Message.CANNOT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE);
        if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
            errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
        if (enterOrderRq.getPrice() % security.getTickSize() != 0)
            errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);

        return errors;
    }
}
