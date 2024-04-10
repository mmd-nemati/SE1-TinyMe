package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
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
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class MinimumExecutionQuantityTest {
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
    private Broker broker1;
    private Broker broker2;

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

        broker1 = Broker.builder().brokerId(1).build();
        broker2 = Broker.builder().brokerId(2).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
    }

    @Test
    void orders_with_negative_min_exec_quantity_are_rejected() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 350, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0, -150));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NEGATIVE)));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 450, 580, broker2.getBrokerId(), shareholder.getShareholderId(), 10, -250));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 3, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NEGATIVE)));
    }

    @Test
    void orders_with_bigger_min_exec_quantity_are_rejected() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 350, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 450));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_BIGGER_THAN_QUANTITY)));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 450, 580, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 500));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 3, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_BIGGER_THAN_QUANTITY)));
    }

    @Test
    void update_orders_change_min_exec_quantity_are_rejected() {
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 445, 545, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW, 200),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 200)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 350, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 150));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY)));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 450, 580, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 250));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 3, List.of(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY)));
    }

    @Test
    void buy_order_not_satisfied_min_exec_quantity_is_rejected() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 70));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 10)).isNull();
    }

    @Test
    void sell_order_not_satisfied_min_exec_quantity_is_rejected() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 100, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 70));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 10)).isNull();
    }

    @Test
    void buy_order_satisfied_min_exec_quantity_is_accepted() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
    }

    @Test
    void buy_order_quantity_with_satisfied_min_exec_is_correct() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 120, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 10).getQuantity()).isEqualTo(70);
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6)).isNull();
    }

    @Test
    void sell_order_quantity_with_satisfied_min_exec_is_correct() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 100, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 10).getQuantity()).isEqualTo(50);
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 3)).isNull();
    }

    @Test
    void update_min_exec_quantity_in_buy_order_after_satisfied_is_rejected() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10, LocalDateTime.now(), Side.BUY, 150, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 50));
        verify(eventPublisher).publish(new OrderRejectedEvent(2, 10, List.of(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY)));
    }

    @Test
    void update_min_exec_quantity_in_sell_order_after_satisfied_is_rejected() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 120, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10, LocalDateTime.now(), Side.SELL, 120, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 50));
        verify(eventPublisher).publish(new OrderRejectedEvent(2, 10, List.of(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY)));
    }

    @Test
    void re_entry_buy_order_doesnt_check_min_exec_quantity() {
        broker2.increaseCreditBy(500_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 100, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 102, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 10));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10, LocalDateTime.now(), Side.BUY, 150, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(new OrderUpdatedEvent(2, 10));
    }

    @Test
    void re_entry_sell_order_doesnt_check_min_exec_quantity() {
        broker2.increaseCreditBy(500_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 100, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 102, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 10));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10, LocalDateTime.now(), Side.SELL, 150, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(new OrderUpdatedEvent(2, 10));
    }

    @Test
    void sell_order_with_rejected_min_exec_rollbacks_correctly() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 100, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 70));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 3).getQuantity()).isEqualTo(50);
    }

    @Test
    void buy_order_with_rejected_min_exec_rollbacks_correctly() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 70));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6).getQuantity()).isEqualTo(50);
    }

    @Test
    void buy_iceberg_order_with_rejected_min_exec_rollbacks_correctly() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 40, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker2.getBrokerId(), shareholder.getShareholderId(), 40, 50));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6).getQuantity()).isEqualTo(40);
    }

    @Test
    void sell_iceberg_order_with_rejected_min_exec_rollbacks_correctly() {
        broker2.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker2, shareholder),
                new Order(6, security, Side.SELL, 40, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 100, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 40, 60));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 3).getQuantity()).isEqualTo(50);
    }
}
