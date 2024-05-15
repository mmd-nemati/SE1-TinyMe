package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.MatchingStateHandler;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.SecurityStateChangedEvent;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.SecurityRepository;
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
    private Security security;
    private List<Order> orders;

    @Autowired
    MatchingStateHandler matchingStateHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;

    @BeforeEach
    void setup() {
        securityRepository.clear();

        security = Security.builder().isin("ABC").build();
        securityRepository.addSecurity(security);
//        broker = Broker.builder().brokerId(0).credit(1_000_000L).build();
//        shareholder = Shareholder.builder().shareholderId(0).build();
//        shareholder.incPosition(security, 100_000);
//        orders = Arrays.asList(
//                new Order(1, security, BUY, 304, 15700, broker, shareholder),
//                new Order(2, security, BUY, 43, 15500, broker, shareholder),
//                new Order(3, security, BUY, 445, 15450, broker, shareholder),
//                new Order(4, security, BUY, 526, 15450, broker, shareholder),
//                new Order(5, security, BUY, 1000, 15400, broker, shareholder),
//                new Order(6, security, Side.SELL, 350, 15800, broker, shareholder),
//                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
//                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
//                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
//                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder)
//        );
//        orders.forEach(order -> security.getOrderBook().enqueue(order));
    }

    @Test
    void verify_change_state_event() {
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION));
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.AUCTION));
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.CONTINUOUS));
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.CONTINUOUS));
        matchingStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq(security.getIsin(), MatchingState.CONTINUOUS));
        verify(eventPublisher).publish(new SecurityStateChangedEvent(security.getIsin(), MatchingState.CONTINUOUS));
    }


}