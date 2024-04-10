package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
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
import org.mockito.ArgumentCaptor;
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
public class OrderHandlerTest {
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
    private Broker broker3;

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
        broker3 = Broker.builder().brokerId(2).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);
    }
    @Test
    void new_order_matched_completely_with_one_trade() {
        Order matchingBuyOrder = new Order(100, security, Side.BUY, 1000, 15500, broker1, shareholder);
        Order incomingSellOrder = new Order(200, security, Side.SELL, 300, 15450, broker2, shareholder);
        security.getOrderBook().enqueue(matchingBuyOrder);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0));

        Trade trade = new Trade(security, matchingBuyOrder.getPrice(), incomingSellOrder.getQuantity(),
                matchingBuyOrder, incomingSellOrder);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 200)));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void new_order_queued_with_no_trade() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
    }
    @Test
    void new_order_matched_partially_with_two_trades() {
        Order matchingBuyOrder1 = new Order(100, security, Side.BUY, 300, 15500, broker1, shareholder);
        Order matchingBuyOrder2 = new Order(110, security, Side.BUY, 300, 15500, broker1, shareholder);
        Order incomingSellOrder = new Order(200, security, Side.SELL, 1000, 15450, broker2, shareholder);
        security.getOrderBook().enqueue(matchingBuyOrder1);
        security.getOrderBook().enqueue(matchingBuyOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,
                incomingSellOrder.getSecurity().getIsin(),
                incomingSellOrder.getOrderId(),
                incomingSellOrder.getEntryTime(),
                incomingSellOrder.getSide(),
                incomingSellOrder.getTotalQuantity(),
                incomingSellOrder.getPrice(),
                incomingSellOrder.getBroker().getBrokerId(),
                incomingSellOrder.getShareholder().getShareholderId(), 0));

        Trade trade1 = new Trade(security, matchingBuyOrder1.getPrice(), matchingBuyOrder1.getQuantity(),
                matchingBuyOrder1, incomingSellOrder);
        Trade trade2 = new Trade(security, matchingBuyOrder2.getPrice(), matchingBuyOrder2.getQuantity(),
                matchingBuyOrder2, incomingSellOrder.snapshotWithQuantity(700));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }

    @Test
    void iceberg_order_behaves_normally_before_being_queued() {
        Order matchingBuyOrder = new Order(100, security, Side.BUY, 1000, 15500, broker1, shareholder);
        Order incomingSellOrder = new IcebergOrder(200, security, Side.SELL, 300, 15450, broker2, shareholder, 100);
        security.getOrderBook().enqueue(matchingBuyOrder);
        Trade trade = new Trade(security, matchingBuyOrder.getPrice(), incomingSellOrder.getQuantity(),
                matchingBuyOrder, incomingSellOrder);

        EventPublisher mockEventPublisher = mock(EventPublisher.class, withSettings().verboseLogging());
        OrderHandler myOrderHandler = new OrderHandler(securityRepository, brokerRepository, shareholderRepository, mockEventPublisher, new Matcher());
        myOrderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,
                incomingSellOrder.getSecurity().getIsin(),
                incomingSellOrder.getOrderId(),
                incomingSellOrder.getEntryTime(),
                incomingSellOrder.getSide(),
                incomingSellOrder.getTotalQuantity(),
                incomingSellOrder.getPrice(),
                incomingSellOrder.getBroker().getBrokerId(),
                incomingSellOrder.getShareholder().getShareholderId(), 100));

        verify(mockEventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(mockEventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void invalid_new_order_with_multiple_errors() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "XXX", -1, LocalDateTime.now(), Side.SELL, 0, 0, -1, -1, 0));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(-1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.UNKNOWN_SECURITY_ISIN,
                Message.INVALID_ORDER_ID,
                Message.ORDER_PRICE_NOT_POSITIVE,
                Message.ORDER_QUANTITY_NOT_POSITIVE,
                Message.INVALID_PEAK_SIZE,
                Message.UNKNOWN_BROKER_ID,
                Message.UNKNOWN_SHAREHOLDER_ID
        );
    }

    @Test
    void invalid_new_order_with_tick_and_lot_size_errors() {
        Security aSecurity = Security.builder().isin("XXX").lotSize(10).tickSize(10).build();
        securityRepository.addSecurity(aSecurity);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "XXX", 1, LocalDateTime.now(), Side.SELL, 12, 1001, 1, shareholder.getShareholderId(), 0));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE,
                Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE
        );
    }

    @Test
    void update_order_causing_no_trades() {
        Order queuedOrder = new Order(200, security, Side.SELL, 500, 15450, broker1, shareholder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, 1, shareholder.getShareholderId(), 0));
        verify(eventPublisher).publish(new OrderUpdatedEvent(1, 200));
    }

    @Test
    void handle_valid_update_with_trades() {
        Order matchingOrder = new Order(1, security, Side.BUY, 500, 15450, broker1, shareholder);
        Order beforeUpdate = new Order(200, security, Side.SELL, 1000, 15455, broker2, shareholder);
        Order afterUpdate = new Order(200, security, Side.SELL, 500, 15450, broker2, shareholder);
        security.getOrderBook().enqueue(matchingOrder);
        security.getOrderBook().enqueue(beforeUpdate);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, broker2.getBrokerId(), shareholder.getShareholderId(), 0));

        Trade trade = new Trade(security, 15450, 500, matchingOrder, afterUpdate);
        verify(eventPublisher).publish(new OrderUpdatedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void invalid_update_with_order_id_not_found() {
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, 1, shareholder.getShareholderId(), 0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, any()));
    }

    @Test
    void invalid_update_with_multiple_errors() {
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "XXX", -1, LocalDateTime.now(), Side.SELL, 0, 0, -1, shareholder.getShareholderId(), 0));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(-1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.UNKNOWN_SECURITY_ISIN,
                Message.UNKNOWN_BROKER_ID,
                Message.INVALID_ORDER_ID,
                Message.ORDER_PRICE_NOT_POSITIVE,
                Message.ORDER_QUANTITY_NOT_POSITIVE,
                Message.INVALID_PEAK_SIZE
        );
    }

    @Test
    void delete_buy_order_deletes_successfully_and_increases_credit() {
        Broker buyBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(buyBroker);
        Order someOrder = new Order(100, security, Side.BUY, 300, 15500, buyBroker, shareholder);
        Order queuedOrder = new Order(200, security, Side.BUY, 1000, 15500, buyBroker, shareholder);
        security.getOrderBook().enqueue(someOrder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), Side.BUY, 200));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 200));
        assertThat(buyBroker.getCredit()).isEqualTo(1_000_000 + 1000*15500);
    }

    @Test
    void delete_sell_order_deletes_successfully_and_does_not_change_credit() {
        Broker sellBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(sellBroker);
        Order someOrder = new Order(100, security, Side.SELL, 300, 15500, sellBroker, shareholder);
        Order queuedOrder = new Order(200, security, Side.SELL, 1000, 15500, sellBroker, shareholder);
        security.getOrderBook().enqueue(someOrder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), Side.SELL, 200));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 200));
        assertThat(sellBroker.getCredit()).isEqualTo(1_000_000);
    }


    @Test
    void invalid_delete_with_order_id_not_found() {
        Broker buyBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(buyBroker);
        Order queuedOrder = new Order(200, security, Side.BUY, 1000, 15500, buyBroker, shareholder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "ABC", Side.SELL, 100));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 100, List.of(Message.ORDER_ID_NOT_FOUND)));
        assertThat(buyBroker.getCredit()).isEqualTo(1_000_000);
    }

    @Test
    void invalid_delete_order_with_non_existing_security() {
        Order queuedOrder = new Order(200, security, Side.BUY, 1000, 15500, broker1, shareholder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "XXX", Side.SELL, 200));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.UNKNOWN_SECURITY_ISIN)));
    }

    @Test
    void buyers_credit_decreases_on_new_order_without_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 0));
        assertThat(broker.getCredit()).isEqualTo(10_000-30*100);
    }

    @Test
    void buyers_credit_decreases_on_new_iceberg_order_without_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 10));
        assertThat(broker.getCredit()).isEqualTo(10_000-30*100);
    }

    @Test
    void credit_does_not_change_on_invalid_new_order() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", -1, LocalDateTime.now(), Side.BUY, 30, 100, broker.getBrokerId(), shareholder.getShareholderId(), 0));
        assertThat(broker.getCredit()).isEqualTo(10_000);
    }

    @Test
    void credit_updated_on_new_order_matched_partially_with_two_orders() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));

        Order matchingSellOrder1 = new Order(100, security, Side.SELL, 30, 500, broker1, shareholder);
        Order matchingSellOrder2 = new Order(110, security, Side.SELL, 20, 500, broker2, shareholder);
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 30*500);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 20*500);
        assertThat(broker3.getCredit()).isEqualTo(100_000 - 50*500 - 50*550);
    }

    @Test
    void new_order_from_buyer_with_not_enough_credit_no_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(1000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 0));
        assertThat(broker.getCredit()).isEqualTo(1000);
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void new_order_from_buyer_with_enough_credit_based_on_trades() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(52_500).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        Order matchingSellOrder1 = new Order(100, security, Side.SELL, 30, 500, broker1, shareholder);
        Order matchingSellOrder2 = new Order(110, security, Side.SELL, 20, 500, broker2, shareholder);
        Order incomingBuyOrder = new Order(200, security, Side.BUY, 100, 550, broker3, shareholder);
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);
        Trade trade1 = new Trade(security, matchingSellOrder1.getPrice(), matchingSellOrder1.getQuantity(),
                incomingBuyOrder, matchingSellOrder1);
        Trade trade2 = new Trade(security, matchingSellOrder2.getPrice(), matchingSellOrder2.getQuantity(),
                incomingBuyOrder.snapshotWithQuantity(700), matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 30*500);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 20*500);
        assertThat(broker3.getCredit()).isEqualTo(0);

        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }

    @Test
    void new_order_from_buyer_with_not_enough_credit_based_on_trades() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(2).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(3).credit(50_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        Order matchingSellOrder1 = new Order(100, security, Side.SELL, 30, 500, broker1, shareholder);
        Order matchingSellOrder2 = new Order(110, security, Side.SELL, 20, 500, broker2, shareholder);
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(50_000);

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void update_buy_order_changing_price_with_no_trades_changes_buyers_credit() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        brokerRepository.addBroker(broker1);
        Order order = new Order(100, security, Side.BUY, 30, 500, broker1, shareholder);
        security.getOrderBook().enqueue(order);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 550, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000 - 1_500);
    }
    @Test
    void update_sell_order_changing_price_with_no_trades_does_not_changes_sellers_credit() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        brokerRepository.addBroker(broker1);
        Order order = new Order(100, security, Side.SELL, 30, 500, broker1, shareholder);
        security.getOrderBook().enqueue(order);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 30, 550, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void update_order_changing_price_with_trades_changes_buyers_and_sellers_credit() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 350*580);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 100*581);
        assertThat(broker3.getCredit()).isEqualTo(100_000 + 430*550 - 350*580 - 100*581 - 50*590);
    }

    @Test
    void update_order_changing_price_with_trades_for_buyer_with_insufficient_quantity_rolls_back() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(54_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        Order originalOrder = orders.get(1).snapshot();
        originalOrder.queue();

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(54_000);
        assertThat(originalOrder).isEqualTo(security.getOrderBook().findByOrderId(Side.BUY, 2));
    }

    @Test
    void update_order_without_trade_decreasing_quantity_changes_buyers_credit() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 400, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(100_000 + 30*550);
    }

    @Test
    void new_sell_order_without_enough_positions_is_rejected() {
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 400, 590, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void update_sell_order_without_enough_positions_is_rejected() {
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 450, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void update_sell_order_with_enough_positions_is_executed() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder1),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder1),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder1),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 250, 570, broker1.getBrokerId(), shareholder.getShareholderId(), 0));

        verify(eventPublisher).publish(any(OrderExecutedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000 + 250)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 99_500 - 251)).isFalse();
    }

    @Test
    void new_buy_order_does_not_check_for_position() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder1),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder1),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder1),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 500, 570, broker3.getBrokerId(), shareholder.getShareholderId(), 0));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 500)).isTrue();
    }

    @Test
    void update_buy_order_does_not_check_for_position() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder1),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder1),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder1),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 500, 545, broker3.getBrokerId(), shareholder1.getShareholderId(), 0));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 500)).isTrue();
    }
///////////////////// NEW
    @Test
    void orders_with_negative_min_exec_quantity_are_rejected() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 350, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0, -150));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NEGATIVE)));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 450, 580, broker3.getBrokerId(), shareholder.getShareholderId(), 0, -250));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 3, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NEGATIVE)));
    }

    @Test
    void orders_with_bigger_min_exec_quantity_are_rejected() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 350, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 450));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_BIGGER_THAN_QUANTITY)));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 450, 580, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 500));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 3, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_BIGGER_THAN_QUANTITY)));
    }

    @Test
    void first_entry_update_orders_change_min_exec_quantity_are_rejected() {
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder, LocalDateTime.now(), OrderStatus.NEW, 200),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, LocalDateTime.now(), OrderStatus.NEW, 200)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 350, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 150));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY)));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 450, 580, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 250));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 3, List.of(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY)));
    }

    @Test
    void buy_order_not_satisfied_min_exec_quantity_is_rejected_not_entered_queue() {
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 70));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 10)).isNull();
    }

    @Test
    void sell_order_not_satisfied_min_exec_quantity_is_rejected_not_entered_queue() {
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 100, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 70));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 10)).isNull();
    }

    @Test
    void order_satisfied_min_exec_quantity_is_accepted() {
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
    }

    @Test
    void update_buy_order_after_satisfied_min_exec_quantity_is_rejected() {
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10, LocalDateTime.now(), Side.BUY, 150, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 50));
        verify(eventPublisher).publish(new OrderRejectedEvent(2, 10, List.of(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY)));
    }

    @Test
    void buy_order_quantity_with_satisfied_min_exec_is_correct() {
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 10).getQuantity()).isEqualTo(50);
    }

    @Test
    void sell_order_quantity_with_satisfied_min_exec_is_correct() {
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 100, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 10).getQuantity()).isEqualTo(50);
    }

    @Test
    void re_entry_buy_order_doesnt_check_min_exec_quantity() {
        broker3.increaseCreditBy(500_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 100, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 102, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 10));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 10, LocalDateTime.now(), Side.BUY, 150, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 45));
        verify(eventPublisher).publish(new OrderUpdatedEvent(2, 10));
    }

    @Test
    void re_entry_sell_order_doesnt_check_min_exec_quantity() {
        broker3.increaseCreditBy(500_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
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
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.SELL, 100, 540, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 70));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 3).getQuantity()).isEqualTo(50);
    }

    @Test
    void buy_order_with_rejected_min_exec_rollbacks_correctly() {
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 50, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 70));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6).getQuantity()).isEqualTo(50);
    }

    @Test
    void buy_iceberg_order_with_rejected_min_exec_rollbacks_correctly() {
        broker3.increaseCreditBy(100_000);
        List<Order> orders = Arrays.asList(
                new Order(3, security, Side.BUY, 50, 545, broker3, shareholder),
                new Order(6, security, Side.SELL, 40, 580, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 10, LocalDateTime.now(), Side.BUY, 100, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 40, 50));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 10, List.of(Message.ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 6).getQuantity()).isEqualTo(40);
    }
}
