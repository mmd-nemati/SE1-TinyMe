package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.MatchingStateHandler;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;

import static org.assertj.core.api.Assertions.assertThat;
@SpringBootTest
@Import(MockedJMSTestConfig.class)
class SecurityMatchingStateTest {

    @Autowired
    MatchingStateHandler matchingStateHandler;
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
    private Broker sellBroker;
    private Broker buyBroker;
    private Shareholder shareholder;

    @BeforeEach
    void setup() {
        securityRepository.clear();

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

    private void mockTradeWithPrice(int price) {
        security.updateLastTradePrice(price);
    }
    private void mockOrderActivation(Order order) {
        if (order.getSide() == BUY)
            security.getQueueInfo().getBuyDisabledOrders().findByOrderId(order.getOrderId()).setStopPriceZero();
        else
            security.getQueueInfo().getSellDisabledOrders().findByOrderId(order.getOrderId()).setStopPriceZero();
    }
    private void changeMatchingState(MatchingState state) {
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), state));
    }

    private EnterOrderRq createNewOrderRequest(long rqId, Order order) {
        return EnterOrderRq.createNewOrderRq(rqId, order.getSecurity().getIsin(), order.getOrderId(),
                order.getEntryTime(), order.getSide(), order.getQuantity(), order.getPrice(), order.getBroker().getBrokerId(),
                order.getShareholder().getShareholderId(), 0, order.getMinimumExecutionQuantity(), order.getStopPrice());
    }

    @Test
    void verify_change_state_event() {
        changeMatchingState(MatchingState.AUCTION);
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.AUCTION));
        changeMatchingState(MatchingState.AUCTION);
        verify(eventPublisher, times(2)).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.AUCTION));
        changeMatchingState(MatchingState.CONTINUOUS);
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.CONTINUOUS));
        changeMatchingState(MatchingState.CONTINUOUS);
        verify(eventPublisher, times(2)).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.CONTINUOUS));
    }

    @Test
    void cant_make_new_stop_order_in_auction_state() {
        changeMatchingState(MatchingState.AUCTION);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(170)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(50).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.CANNOT_ADD_STOP_ORDER_IN_AUCTION_STATE)));
    }

    @Test
    void cant_update_unactivated_stop_order_in_auction_state() {
        mockTradeWithPrice(100);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(170)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(120).build();
        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));

        changeMatchingState(MatchingState.AUCTION);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, security.getIsin(), 6,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 120));
        verify(eventPublisher).publish(new OrderRejectedEvent(2, 6, List.of(Message.CANNOT_UPDATE_STOP_ORDER_IN_AUCTION_STATE)));
    }

    @Test
    void cant_delete_unactivated_stop_order_in_auction_state() {
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(170)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(120).build();
        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));

        changeMatchingState(MatchingState.AUCTION);

        orderHandler.handleDeleteOrder(new DeleteOrderRq(2, security.getIsin(), Side.BUY, 6));
        verify(eventPublisher).publish(new OrderRejectedEvent(2, 6, List.of(Message.CANNOT_DELETE_STOP_ORDER_IN_AUCTION_STATE)));
    }

    @Test
    void cant_make_new_order_with_min_exec_in_auction_state() {
        changeMatchingState(MatchingState.AUCTION);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(170)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(50).stopPrice(0).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.CANNOT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE)));
    }

    @Test
    void new_orders_shouldnt_start_matching_in_auction_state() {
        changeMatchingState(MatchingState.AUCTION);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(170)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o2 = Order.builder().orderId(10).security(security).side(Side.SELL).quantity(50).price(170)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();


        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));

        Trade t1 = new Trade(security, 170, 50, o1, o2);
        verify(eventPublisher, never()).publish(new OrderExecutedEvent(2, 10, List.of(new TradeDTO(t1))));
    }

    @Test
    void correct_opening_price_event_with_new_orders() {
        mockTradeWithPrice(170);
        changeMatchingState(MatchingState.AUCTION);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(50).price(180)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o2 = Order.builder().orderId(10).security(security).side(Side.SELL).quantity(50).price(170)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o3 = Order.builder().orderId(7).security(security).side(Side.BUY).quantity(70).price(180)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o4 = Order.builder().orderId(7).security(security).side(Side.SELL).quantity(120).price(180)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 0, 0));

        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 170, 50));

        orderHandler.handleEnterOrder(createNewOrderRequest(3, o3));
        verify(eventPublisher, times(2)).publish(new OpeningPriceEvent(security.getIsin(), 170, 50));

        orderHandler.handleEnterOrder(createNewOrderRequest(4, o4));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 180, 120));
    }

    @Test
    void unactivated_order_actives_after_auction_to_auction() {
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(100)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(40).build();
        Order o2 = Order.builder().orderId(10).security(security).side(Side.SELL).quantity(100).price(50)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o3 = Order.builder().orderId(7).security(security).side(Side.BUY).quantity(100).price(200)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o4 = Order.builder().orderId(8).security(security).side(Side.BUY).quantity(100).price(40)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o5 = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(100).price(220)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        verify(eventPublisher, never()).publish(new OrderActivatedEvent(1, 6));

        changeMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));
        orderHandler.handleEnterOrder(createNewOrderRequest(3, o3));
        orderHandler.handleEnterOrder(createNewOrderRequest(4, o4));
        orderHandler.handleEnterOrder(createNewOrderRequest(5, o5));

        changeMatchingState(MatchingState.AUCTION);
        verify(eventPublisher).publish(new TradeEvent(security.getIsin(), 50, 100, 7, 10));
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 6));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 6));
    }

    @Test
    void activated_orders_in_auction_make_trade_in_next_auction_after_update() {
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(100)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(40).build();
        Order o2 = Order.builder().orderId(10).security(security).side(Side.SELL).quantity(100).price(50)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o3 = Order.builder().orderId(7).security(security).side(Side.BUY).quantity(100).price(200)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o4 = Order.builder().orderId(8).security(security).side(Side.BUY).quantity(100).price(40)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o5 = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(100).price(220)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));

        changeMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));
        orderHandler.handleEnterOrder(createNewOrderRequest(3, o3));
        orderHandler.handleEnterOrder(createNewOrderRequest(4, o4));
        orderHandler.handleEnterOrder(createNewOrderRequest(5, o5));

        changeMatchingState(MatchingState.AUCTION);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, security.getIsin(), 6,
                LocalDateTime.now(), Side.BUY, 100, 220, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 220, 100));

        changeMatchingState(MatchingState.AUCTION);
        verify(eventPublisher).publish(new TradeEvent(security.getIsin(), 220, 100, 6, 11));
    }

    @Test
    void correct_broker_credit_of_orders_in_auction_state() {
        changeMatchingState(MatchingState.AUCTION);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(170)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o2 = Order.builder().orderId(10).security(security).side(Side.SELL).quantity(50).price(170)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));

        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 6));
        verify(eventPublisher).publish(new OrderAcceptedEvent(2, 10));

        assertThat(buyBroker.getCredit()).isEqualTo(100_000_000 - 100 * 170);
        assertThat(sellBroker.getCredit()).isZero();

        changeMatchingState(MatchingState.AUCTION);
        assertThat(buyBroker.getCredit()).isEqualTo(100_000_000 - 100 * 170);
        assertThat(sellBroker.getCredit()).isEqualTo(50 * 170);
    }

    @Test
    void correct_delete_of_stop_order_after_activation_in_auction_state() {
        mockTradeWithPrice(200);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(170)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(300).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        verify(eventPublisher, never()).publish(new OrderActivatedEvent(1, 6));
        changeMatchingState(MatchingState.AUCTION);

        mockOrderActivation(o1);

        orderHandler.handleDeleteOrder(new DeleteOrderRq(2, security.getIsin(), Side.BUY, 6));
        verify(eventPublisher).publish(new OrderDeletedEvent(2, 6));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 0, 0));
    }

    @Test
    void correct_normal_auction_matching() {
        changeMatchingState(MatchingState.AUCTION);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(170)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o2 = Order.builder().orderId(10).security(security).side(Side.SELL).quantity(50).price(170)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o3 = Order.builder().orderId(7).security(security).side(Side.BUY).quantity(120).price(400)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o4 = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(100).price(380)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));
        orderHandler.handleEnterOrder(createNewOrderRequest(3, o3));
        orderHandler.handleEnterOrder(createNewOrderRequest(4, o4));

        changeMatchingState(MatchingState.AUCTION);

        verify(eventPublisher).publish(new TradeEvent(security.getIsin(), 380, 50, 7, 10));
        verify(eventPublisher).publish(new TradeEvent(security.getIsin(), 380, 70, 7, 11));
    }

    @Test
    void correct_paying_dept_of_auction_to_buy_broker() {
        mockTradeWithPrice(380);
        changeMatchingState(MatchingState.AUCTION);
        Order o3 = Order.builder().orderId(7).security(security).side(Side.BUY).quantity(120).price(400)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o4 = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(150).price(380)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(3, o3));
        orderHandler.handleEnterOrder(createNewOrderRequest(4, o4));
        long prevCredit = buyBroker.getCredit();

        changeMatchingState(MatchingState.AUCTION);

        int dept = (400 - 380) * 120;
        assertThat(buyBroker.getCredit()).isEqualTo(prevCredit + dept);
    }

    @Test
    void consider_all_of_iceberg_quantity_to_calculate_opening_price(){
        changeMatchingState(MatchingState.AUCTION);
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(150)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o2 = IcebergOrder.builder().orderId(10).security(security).side(Side.SELL).quantity(50).price(150)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .peakSize(20).displayedQuantity(10).build();

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 0, 0));

        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 150, 50));
        verify(eventPublisher, never()).publish(new OpeningPriceEvent(security.getIsin(), 150, 20));
    }

    @Test
    void correct_continuous_matching_for_remainder_order_from_auction() {
        mockTradeWithPrice(380);
        changeMatchingState(MatchingState.AUCTION);
        Order o1 = Order.builder().orderId(7).security(security).side(Side.BUY).quantity(120).price(400)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o2 = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(150).price(380)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o3 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(420)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Trade remainderTrade = new Trade(security, 380, 30, o3, o2);

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));

        changeMatchingState(MatchingState.CONTINUOUS);
        verify(eventPublisher).publish(new TradeEvent(security.getIsin(), 380, 120, 7, 11));
        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 380, 120));

        orderHandler.handleEnterOrder(createNewOrderRequest(3, o3));
        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 6));
        verify(eventPublisher).publish(new OrderExecutedEvent(3, 6, List.of(new TradeDTO(remainderTrade))));
    }

    @Test
    void nothing_new_matches_with_continuous_to_auction() {
        Order o1 = Order.builder().orderId(7).security(security).side(Side.BUY).quantity(120).price(400)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o2 = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(150).price(380)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o3 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(420)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Trade t1 = new Trade(security, 400, 120, o1, o2);
        Trade t2 = new Trade(security, 380, 30, o3, o2);

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));
        orderHandler.handleEnterOrder(createNewOrderRequest(3, o3));
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 11, List.of(new TradeDTO(t1))));
        verify(eventPublisher).publish(new OrderExecutedEvent(3, 6, List.of(new TradeDTO(t2))));

        changeMatchingState(MatchingState.CONTINUOUS);
        verify(eventPublisher, times(2)).publish(any(OrderExecutedEvent.class));
        verify(eventPublisher, never()).publish(any(TradeEvent.class));
        verify(eventPublisher, never()).publish(any(OpeningPriceEvent.class));
    }

    @Test
    void new_activated_orders_match_in_auction_to_continuous(){
        Order o1 = Order.builder().orderId(6).security(security).side(Side.BUY).quantity(100).price(100)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(40).build();
        Order o2 = Order.builder().orderId(10).security(security).side(Side.SELL).quantity(100).price(50)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o3 = Order.builder().orderId(7).security(security).side(Side.BUY).quantity(100).price(200)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o4 = Order.builder().orderId(8).security(security).side(Side.BUY).quantity(100).price(40)
                .broker(buyBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o5 = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(100).price(220)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Order o6 = Order.builder().orderId(12).security(security).side(Side.SELL).quantity(100).price(50)
                .broker(sellBroker).shareholder(shareholder).entryTime(LocalDateTime.now()).status(OrderStatus.NEW)
                .minimumExecutionQuantity(0).stopPrice(0).build();
        Trade continuousTrade = new Trade(security, 100, 100, o1, o6);
        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));

        changeMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));
        orderHandler.handleEnterOrder(createNewOrderRequest(3, o3));
        orderHandler.handleEnterOrder(createNewOrderRequest(4, o4));
        orderHandler.handleEnterOrder(createNewOrderRequest(5, o5));


        changeMatchingState(MatchingState.CONTINUOUS);
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 6));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 6));
        orderHandler.handleEnterOrder(createNewOrderRequest(6, o6));

        verify(eventPublisher).publish(new OrderExecutedEvent(6, 12, List.of(new TradeDTO(continuousTrade))));
    }
}