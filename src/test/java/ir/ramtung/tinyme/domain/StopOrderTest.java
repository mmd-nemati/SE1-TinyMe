package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
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
        buyBroker.increaseCreditBy(100_000_000);
        brokerRepository.addBroker(sellBroker);
        brokerRepository.addBroker(buyBroker);
    }

    @Test
    void reject_min_exec_for_stop_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 350, 580, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 150, 20));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6,
                List.of(Message.STOP_ORDER_CANNOT_HAVE_MINIMUM_EXEC_QUANTITY)));
    }

    @Test
    void reject_stop_limit_price_for_iceberg_order() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 100, 590, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 40, 0, 20));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10,
                List.of(Message.STOP_ORDER_CANNOT_BE_ICEBERG_TOO)));
    }

    @Test
    void reject_negative_stop_limit_price() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 350, 580, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, -100));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6,
                List.of(Message.STOP_PRICE_CANNOT_BE_NEGATIVE)));
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
    void activated_stop_order_has_correct_credit_after_trade() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 150, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.BUY, 50, 550, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));


        verify(eventPublisher).publish(any(OrderExecutedEvent.class));


        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        assertThat(buyBroker.getCredit()).isEqualTo(100_000_000 - 50 * 545 - 25 * 545);

    }

    @Test
    void reject_update_stop_price_after_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 150, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.BUY, 50, 550, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 200, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));
        verify(eventPublisher).publish(any(OrderActivatedEvent.class));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(4, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 20));

        verify(eventPublisher).publish(new OrderRejectedEvent(4, 10, List.of(Message.CANNOT_CHANGE_STOP_PRICE_FOR_ACTIVATED)));
    }

    @Test
    void accept_update_before_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 10, 570, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 120));

        verify(eventPublisher).publish(any(OrderUpdatedEvent.class));
    }

    @Test
    void reject_update_not_allowed_fields_before_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10,
                LocalDateTime.now(), Side.SELL, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 80));

        verify(eventPublisher).publish(new OrderRejectedEvent(2, 10, List.of(Message.CANNOT_CHANGE_NOT_ALLOWED_PARAMETERS_BEFORE_ACTIVATION)));
    }

    @Test
    void reject_update_not_allowed_to_change_min_Execution_Quantity() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 10, 80));

        verify(eventPublisher).publish(new OrderRejectedEvent(2, 10, List.of(Message.STOP_ORDER_CANNOT_HAVE_MINIMUM_EXEC_QUANTITY)));
    }

    @Test
    void reject_update_not_allowed_to_convert_to_icebergOrder() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10,
                LocalDateTime.now(), Side.SELL, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 10, 0, 80));

        verify(eventPublisher).publish(new OrderRejectedEvent(2, 10, List.of(Message.STOP_ORDER_CANNOT_BE_ICEBERG_TOO)));
    }

    @Test
    void delete_stop_order_before_activation() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(2, security.getIsin(), Side.BUY, 10));
        verify(eventPublisher).publish(new OrderDeletedEvent(2, 10));
    }

    @Test
    void delete_stop_order_before_activation_not_found() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(2, security.getIsin(), Side.BUY, 12));

        verify(eventPublisher).publish(new OrderRejectedEvent(2, 12, List.of(Message.ORDER_ID_NOT_FOUND)));
    }

    @Test
    void activate_multiple_buy_stop_order_enter_before_trade() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 11,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 120));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 12,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 110));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 50, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.BUY, 50, 550, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderActivatedEvent(3, 10));
        verify(eventPublisher).publish(new OrderActivatedEvent(5, 12));
        verify(eventPublisher).publish(new OrderActivatedEvent(4, 11));
    }

    @Test
    void activate_multiple_sell_stop_order_enter_before_trade() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.SELL, 25, 580, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 600));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 11,
                LocalDateTime.now(), Side.SELL, 25, 580, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 660));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 12,
                LocalDateTime.now(), Side.SELL, 25, 580, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 630));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.BUY, 50, 545, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 3,
                LocalDateTime.now(), Side.SELL, 50, 550, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderActivatedEvent(3, 10));
        verify(eventPublisher).publish(new OrderActivatedEvent(5, 12));
        verify(eventPublisher).publish(new OrderActivatedEvent(4, 11));
    }

    @Test
    void activated_stop_order_publishes_executed_after_trade() {
        security.updateLastTradePrice(300);
        Order o1 = new Order(6, security, Side.SELL, 150, 545, sellBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 0);
        Order o2 = new Order(10, security, Side.BUY, 25, 580, buyBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 100);
        Order o3 = new Order(11, security, Side.BUY, 25, 580, buyBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 110);
        Order o4 = new Order(12, security, Side.BUY, 25, 580, buyBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 110);

        Trade t1 = new Trade(security, 545, 25, o1, o2);
        Trade t2 = new Trade(security, 545, 25, o1, o3);
        Trade t3 = new Trade(security, 545, 25, o1, o4);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6,
                LocalDateTime.now(), Side.SELL, 150, 545, sellBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 11,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 120));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 12,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 130));

        verify(eventPublisher).publish(new OrderExecutedEvent(3, 10, List.of(new TradeDTO(t1))));
        verify(eventPublisher).publish(new OrderExecutedEvent(4, 11, List.of(new TradeDTO(t2))));
        verify(eventPublisher).publish(new OrderExecutedEvent(5, 12, List.of(new TradeDTO(t3))));
    }

    @Test
    void update_stop_order_before_activation_not_found() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 100));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 12,
                LocalDateTime.now(), Side.SELL, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 80));

        verify(eventPublisher).publish(new OrderRejectedEvent(2, 12, List.of(Message.ORDER_ID_NOT_FOUND)));
    }
}
