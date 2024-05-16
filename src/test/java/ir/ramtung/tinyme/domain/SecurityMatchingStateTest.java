package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.MatchingStateHandler;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.OpeningPriceEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.event.SecurityStateChangedEvent;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
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
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.*;
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
    private List<Order> orders;
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

    private EnterOrderRq createNewOrderRequest(long rqId, Order order) {
        return EnterOrderRq.createNewOrderRq(rqId, order.getSecurity().getIsin(), order.getOrderId(),
                order.getEntryTime(), order.getSide(), order.getQuantity(), order.getPrice(), order.getBroker().getBrokerId(),
                order.getShareholder().getShareholderId(), 0, order.getMinimumExecutionQuantity(), order.getStopPrice());
    }

    @Test
    void verify_change_state_event() {
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.AUCTION));
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.CONTINUOUS));
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.CONTINUOUS));
    }

    @Test
    void cant_make_new_stop_order_in_auction_state() {
        mockTradeWithPrice(100);
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));
        Order o1 = new Order(6, security, Side.BUY, 100, 170, buyBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 50);

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.CANNOT_ADD_STOP_ORDER_IN_AUCTION_STATE)));
    }

    @Test
    void cant_update_unactivated_stop_order_in_auction_state() {
        mockTradeWithPrice(100);
        Order o1 = new Order(6, security, Side.BUY, 100, 170, buyBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 120);
        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));

        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, security.getIsin(), 6,
                LocalDateTime.now(), Side.BUY, 25, 580, buyBroker.getBrokerId(),
                shareholder.getShareholderId(), 0, 0, 120));
        verify(eventPublisher).publish(new OrderRejectedEvent(2, 6, List.of(Message.CANNOT_UPDATE_STOP_ORDER_IN_AUCTION_STATE)));
    }

    @Test
    void cant_make_new_order_with_min_exec_in_auction_state() {
        mockTradeWithPrice(100);
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));
        Order o1 = new Order(6, security, Side.BUY, 100, 170, buyBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 50, 0);

        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.CANNOT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE)));
    }

    @Test
    void new_orders_shouldnt_start_matching_in_auction_state() {
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));
        Order o1 = new Order(6, security, Side.BUY, 100, 170, buyBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 0);
        Order o2 = new Order(10, security, Side.SELL, 50, 170, sellBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 0);


        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));

        Trade t1 = new Trade(security, 170, 50, o1, o2);
        verify(eventPublisher, never()).publish(new OrderExecutedEvent(2, 10, List.of(new TradeDTO(t1))));

    }

    @Test
    void opening_price_event_in_continuous_state() {
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));
        Order o1 = new Order(6, security, Side.BUY, 100, 170, buyBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 0);
        Order o2 = new Order(10, security, Side.SELL, 50, 170, sellBroker, shareholder,
                LocalDateTime.now(), OrderStatus.NEW, 0, 0);


        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));

        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 170, 50));
    }

//    @Test
//    void orders_start_trading_after_virtual_change() {
//        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));
//        Order o1 = new Order(6, security, Side.BUY, 100, 170, buyBroker, shareholder,
//                LocalDateTime.now(), OrderStatus.NEW, 0, 0);
//        Order o2 = new Order(10, security, Side.SELL, 50, 170, sellBroker, shareholder,
//                LocalDateTime.now(), OrderStatus.NEW, 0, 0);
//
//
//        orderHandler.handleEnterOrder(createNewOrderRequest(1, o1));
//        orderHandler.handleEnterOrder(createNewOrderRequest(2, o2));
//        verify(eventPublisher).publish(new OpeningPriceEvent(security.getIsin(), 170, 50));
//
//        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));
//
//        Trade t1 = new Trade(security, 170, 50, o1, o2);
//        verify(eventPublisher).publish(new OrderExecutedEvent(2, 10, List.of(new TradeDTO(t1))));
//
//    }
}