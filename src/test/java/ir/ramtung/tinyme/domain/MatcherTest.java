package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class MatcherTest {
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    private Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker = Broker.builder().credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                Order.builder().orderId(1).security(security).side(BUY).quantity(304).price(15700).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(2).security(security).side(BUY).quantity(43).price(15500).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(3).security(security).side(BUY).quantity(445).price(15450).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(4).security(security).side(BUY).quantity(526).price(15450).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(5).security(security).side(BUY).quantity(1000).price(15400).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(6).security(security).side(Side.SELL).quantity(350).price(15800).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(7).security(security).side(Side.SELL).quantity(285).price(15810).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(8).security(security).side(Side.SELL).quantity(800).price(15810).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(9).security(security).side(Side.SELL).quantity(340).price(15820).broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(10).security(security).side(Side.SELL).quantity(65).price(15820).broker(broker).shareholder(shareholder).build()
        );
        orders.forEach(order -> orderBook.enqueue(order));
    }

    @Test
    void new_sell_order_matches_completely_with_part_of_the_first_buy() {
        Order order = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(100).price(15600)
                .broker(broker).shareholder(shareholder).build();
        Trade trade = new Trade(security, 15700, 100, orders.get(0), order);
        MatchResult result = matcher.match(order);
        assertThat(result.remainder().getQuantity()).isEqualTo(0);
        assertThat(result.trades()).containsExactly(trade);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getQuantity()).isEqualTo(204);
    }

    @Test
    void new_sell_order_matches_partially_with_the_first_buy() {
        Order order = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(500).price(15600)
                .broker(broker).shareholder(shareholder).build();
        Trade trade = new Trade(security, 15700, 304, orders.get(0), order);
        MatchResult result = matcher.match(order);
        assertThat(result.remainder().getQuantity()).isEqualTo(196);
        assertThat(result.trades()).containsExactly(trade);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(2);
    }

    @Test
    void new_sell_order_matches_partially_with_two_buys() {
        Order order = Order.builder().orderId(11).security(security).side(Side.SELL).quantity(500).price(15500)
                .broker(broker).shareholder(shareholder).build();
        Trade trade1 = new Trade(security, 15700, 304, orders.get(0), order);
        Trade trade2 = new Trade(security, 15500, 43, orders.get(1), order.snapshotWithQuantity(196));
        MatchResult result = matcher.match(order);
        assertThat(result.remainder().getQuantity()).isEqualTo(153);
        assertThat(result.trades()).containsExactly(trade1, trade2);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(3);
    }

    @Test
    void new_buy_order_matches_partially_with_the_entire_sell_queue() {
        Order order = Order.builder().orderId(11).security(security).side(BUY).quantity(2000).price(15820)
                .broker(broker).shareholder(shareholder).build();
        List<Trade> trades = new ArrayList<>();
        int totalTraded = 0;
        for (Order o : orders.subList(5, 10)) {
            trades.add(new Trade(security, o.getPrice(), o.getQuantity(),
                    order.snapshotWithQuantity(order.getQuantity() - totalTraded), o));
            totalTraded += o.getQuantity();
        }

        MatchResult result = matcher.match(order);
        assertThat(result.remainder().getQuantity()).isEqualTo(160);
        assertThat(result.trades()).isEqualTo(trades);
        assertThat(security.getOrderBook().getSellQueue()).isEmpty();
    }

    @Test
    void new_buy_order_does_not_match() {
        Order order = Order.builder().orderId(11).security(security).side(BUY).quantity(2000).price(15500)
                .broker(broker).shareholder(shareholder).build();
        MatchResult result = matcher.match(order);
        assertThat(result.remainder()).isEqualTo(order);
        assertThat(result.trades()).isEmpty();
    }

    @Test
    void iceberg_order_in_queue_matched_completely_after_three_rounds() {
        security = Security.builder().build();
        broker = Broker.builder().build();
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                IcebergOrder.builder().orderId(1).security(security).side(BUY).quantity(450).price(15450)
                        .broker(broker).shareholder(shareholder).peakSize(200).build(),
                Order.builder().orderId(2).security(security).side(BUY).quantity(70).price(15450)
                        .broker(broker).shareholder(shareholder).build(),
                Order.builder().orderId(3).security(security).side(BUY).quantity(1000).price(15400)
                        .broker(broker).shareholder(shareholder).build()
        );
        orders.forEach(order -> orderBook.enqueue(order));
        Order order = Order.builder().orderId(4).security(security).side(Side.SELL).quantity(600).price(15450)
                .broker(broker).shareholder(shareholder).build();
        List<Trade> trades = List.of(
                new Trade(security, 15450, 200, orders.get(0).snapshotWithQuantity(200), order.snapshotWithQuantity(600)),
                new Trade(security, 15450, 70, orders.get(1).snapshotWithQuantity(70), order.snapshotWithQuantity(400)),
                new Trade(security, 15450, 200, orders.get(0).snapshotWithQuantity(200), order.snapshotWithQuantity(330)),
                new Trade(security, 15450, 50, orders.get(0).snapshotWithQuantity(50), order.snapshotWithQuantity(130))
        );

        MatchResult result = matcher.match(order);

        assertThat(result.remainder().getQuantity()).isEqualTo(80);
        assertThat(result.trades()).isEqualTo(trades);
    }


    @Test
    void insert_iceberg_and_match_until_quantity_is_less_than_peak_size() {
        security = Security.builder().isin("TEST").build();
        shareholder.incPosition(security, 1_000);
        security.getOrderBook().enqueue(
                Order.builder().orderId(1).security(security).side(Side.SELL).quantity(100).price(10)
                        .broker(broker).shareholder(shareholder).build()
        );

        Order order = IcebergOrder.builder().orderId(1).security(security).side(BUY).quantity(120).price(10)
                .broker(broker).shareholder(shareholder).peakSize(40).build();
        MatchResult result = matcher.execute(order);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(result.trades()).hasSize(1);
        assertThat(security.getOrderBook().getSellQueue()).hasSize(0);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(20);
    }

}
