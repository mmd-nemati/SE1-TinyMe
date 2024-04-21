package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
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
    void accept_activate_stop_order() {
        buyBroker.increaseCreditBy(100_000);
//        Order sellOrder = new Order(11, security, Side.SELL, 50, 545, sellBroker, shareholder);
//        Order buyOrder = new Order(3, security, Side.BUY, 50, 550, buyBroker, shareholder);
//        security.getOrderBook().enqueue(sellOrder);
//        MatchResult result = matcher.match(buyOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 50, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.BUY, 50, 550, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        verify(eventPublisher).publish(any(OrderActivatedEvent.class));
    }

}
