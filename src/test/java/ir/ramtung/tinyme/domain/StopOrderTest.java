package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class StopOrderTest {
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;
    @Autowired
    private Matcher matcher;
    private Security security;
    private Shareholder shareholder;
    private Broker sellBroker;
    private Broker buyBroker;


    @BeforeEach
    void setup() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("ABC").build();
        securityRepository.addSecurity(security);

        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder);

        sellBroker = Broker.builder().brokerId(1).build();
        buyBroker = Broker.builder().brokerId(2).build();
        brokerRepository.addBroker(sellBroker);
        brokerRepository.addBroker(buyBroker);
    }
    @Test
    void reject_min_exec_for_stop_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 350, 580, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 150, 20));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6,
                List.of(Message.STOP_LIMIT_AND_MINIMUM_EXEC_QUANTITY)));
    }

    @Test
    void reject_stop_limit_price_for_iceberg_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 100, 590, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 40, 0, 20));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10,
                List.of(Message.STOP_ORDER_IS_ICEBERG_TOO)));
    }

    @Test
    void reject_negative_stop_limit_price() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 350, 580, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, -100));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6,
                List.of(Message.STOP_PRICE_NEGATIVE)));
    }

    @Test
    void accept_enter_stop_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 350, 580, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
    }

    @Test
    void activate_stop_orders_enter_after_trade() {
        buyBroker.increaseCreditBy(100_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 50, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.BUY, 50, 550, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        verify(eventPublisher).publish(new OrderActivatedEvent(3, 10));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 11,
                LocalDateTime.now(), Side.SELL, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 1000));

        verify(eventPublisher).publish(new OrderActivatedEvent(4, 11));
    }

    @Test
    void activate_stop_order_enter_before_trade() {
        buyBroker.increaseCreditBy(100_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 50, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.BUY, 50, 550, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderActivatedEvent(3, 10));
    }

    @Test
    void activated_stop_order_makes_trade() {
        buyBroker.increaseCreditBy(100_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 150, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.BUY, 50, 550, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));


        verify(eventPublisher).publish(any(OrderExecutedEvent.class));
//        verify(eventPublisher).publish(any(OrderExecutedEvent.class));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

//        verify(eventPublisher).publish(any(OrderActivatedEvent.class));
        verify(eventPublisher).publish(any(OrderExecutedEvent.class));
//        verify(orderHandler).applyExecuteEffects(any(OrderExecutedEvent.class));
    }

    @Test
    void reject_update_stop_price_after_activation() {
        buyBroker.increaseCreditBy(100_000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 150, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.BUY, 50, 550, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));
        verify(eventPublisher).publish(any(OrderActivatedEvent.class));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(4, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 20));
        // TODO -> Message: Can't change after activation
        verify(eventPublisher).publish(any(OrderRejectedEvent.class));
    }

    @Test
    void accept_update_before_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 10, 570, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 120));
        // TODO -> Message?
        verify(eventPublisher).publish(any(OrderUpdatedEvent.class));
    }

//    @Test
//    void reject_update_not_allowed_fields_after_activation() {
//        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
//                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 0, 100));
//
//        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10,
//                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
//                shareholder.getShareholderId(), 0, 0, 100));
//        // TODO -> Message?
//        verify(eventPublisher).publish(any(OrderUpdatedEvent.class));
//    }

//    @Test
//    void delete_stop_order_before_activation() {
//
//    }
//    @Test
//    void
}
