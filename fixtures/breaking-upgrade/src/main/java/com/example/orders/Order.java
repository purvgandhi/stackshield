package com.example.orders;

import java.math.BigDecimal;

public record Order(long id, String customer, int quantity, BigDecimal unitPrice) {

    public BigDecimal total() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
