package com.example.orders;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private final List<Order> orders = List.of(
            new Order(1, "ada", 2, new BigDecimal("19.99")),
            new Order(2, "linus", 1, new BigDecimal("249.00")),
            new Order(3, "grace", 5, new BigDecimal("4.50")));

    public List<Order> findAll() {
        return orders;
    }

    public Optional<Order> findById(long id) {
        return orders.stream().filter(o -> o.id() == id).findFirst();
    }

    public BigDecimal revenue() {
        return orders.stream().map(Order::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
