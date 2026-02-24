package com.food.ordering.system.order.service.domain;

import com.food.ordering.system.domain.valueobject.CustomerId;
import com.food.ordering.system.domain.valueobject.Money;
import com.food.ordering.system.domain.valueobject.OrderId;
import com.food.ordering.system.domain.valueobject.OrderStatus;
import com.food.ordering.system.domain.valueobject.ProductId;
import com.food.ordering.system.domain.valueobject.RestaurantId;
import com.food.ordering.system.order.service.domain.dto.create.CreateOrderCommand;
import com.food.ordering.system.order.service.domain.dto.create.CreateOrderResponse;
import com.food.ordering.system.order.service.domain.dto.create.OrderAddress;
import com.food.ordering.system.order.service.domain.entity.Order;
import com.food.ordering.system.order.service.domain.entity.OrderItem;
import com.food.ordering.system.order.service.domain.entity.Product;
import com.food.ordering.system.order.service.domain.event.OrderCreatedEvent;
import com.food.ordering.system.order.service.domain.exception.OrderDomainException;
import com.food.ordering.system.order.service.domain.mapper.OrderDataMapper;
import com.food.ordering.system.order.service.domain.outbox.model.payment.OrderPaymentEventPayload;
import com.food.ordering.system.order.service.domain.outbox.scheduler.payment.PaymentOutboxHelper;
import com.food.ordering.system.order.service.domain.valueobject.StreetAddress;
import com.food.ordering.system.order.service.domain.valueobject.TrackingId;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.saga.SagaStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreateCommandHandlerTest {

    private static final UUID CUSTOMER_ID   = UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb41");
    private static final UUID RESTAURANT_ID = UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb45");
    private static final UUID PRODUCT_ID    = UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb48");
    private static final UUID ORDER_ID      = UUID.fromString("15a497c1-0f4b-4eff-b9f4-c402c8c07afb");
    private static final UUID TRACKING_ID   = UUID.fromString("15a497c1-0f4b-4eff-b9f4-c402c8c07afc");

    @Mock private OrderCreateHelper orderCreateHelper;
    @Mock private OrderDataMapper orderDataMapper;
    @Mock private PaymentOutboxHelper paymentOutboxHelper;
    @Mock private OrderSagaHelper orderSagaHelper;

    @InjectMocks
    private OrderCreateCommandHandler handler;

    private CreateOrderCommand command;
    private Order order;
    private OrderCreatedEvent orderCreatedEvent;
    private CreateOrderResponse expectedResponse;
    private OrderPaymentEventPayload payload;

    @BeforeEach
    void setUp() {
        command = CreateOrderCommand.builder()
                .customerId(CUSTOMER_ID)
                .restaurantId(RESTAURANT_ID)
                .address(OrderAddress.builder()
                        .street("street_1")
                        .postalCode("1000AB")
                        .city("Paris")
                        .build())
                .price(new BigDecimal("200.00"))
                .items(List.of(
                        com.food.ordering.system.order.service.domain.dto.create.OrderItem.builder()
                                .productId(PRODUCT_ID)
                                .quantity(1)
                                .price(new BigDecimal("50.00"))
                                .subTotal(new BigDecimal("50.00"))
                                .build(),
                        com.food.ordering.system.order.service.domain.dto.create.OrderItem.builder()
                                .productId(PRODUCT_ID)
                                .quantity(3)
                                .price(new BigDecimal("50.00"))
                                .subTotal(new BigDecimal("150.00"))
                                .build()))
                .build();

        order = Order.builder()
                .orderId(new OrderId(ORDER_ID))
                .customerId(new CustomerId(CUSTOMER_ID))
                .restaurantId(new RestaurantId(RESTAURANT_ID))
                .deliveryAddress(new StreetAddress(UUID.randomUUID(), "street_1", "1000AB", "Paris"))
                .price(new Money(new BigDecimal("200.00")))
                .items(List.of(
                        OrderItem.builder()
                                .product(new Product(new ProductId(PRODUCT_ID)))
                                .price(new Money(new BigDecimal("50.00")))
                                .quantity(1)
                                .subTotal(new Money(new BigDecimal("50.00")))
                                .build()))
                .trackingId(new TrackingId(TRACKING_ID))
                .orderStatus(OrderStatus.PENDING)
                .build();

        orderCreatedEvent = new OrderCreatedEvent(order, ZonedDateTime.now());

        expectedResponse = CreateOrderResponse.builder()
                .orderTrackingId(TRACKING_ID)
                .orderStatus(OrderStatus.PENDING)
                .message("Order created successfully")
                .build();

        payload = OrderPaymentEventPayload.builder()
                .orderId(ORDER_ID.toString())
                .customerId(CUSTOMER_ID.toString())
                .price(new BigDecimal("200.00"))
                .createdAt(ZonedDateTime.now())
                .paymentOrderStatus("PENDING")
                .build();
    }

    @Test
    void createOrder_returnsResponseFromMapper() {
        when(orderCreateHelper.persistOrder(command)).thenReturn(orderCreatedEvent);
        when(orderDataMapper.orderToCreateOrderResponse(order, "Order created successfully")).thenReturn(expectedResponse);
        when(orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent)).thenReturn(payload);
        when(orderSagaHelper.orderStatusToSagaStatus(OrderStatus.PENDING)).thenReturn(SagaStatus.STARTED);

        CreateOrderResponse result = handler.createOrder(command);

        assertSame(expectedResponse, result);
    }

    @Test
    void createOrder_responseHasPendingStatusAndSuccessMessage() {
        when(orderCreateHelper.persistOrder(command)).thenReturn(orderCreatedEvent);
        when(orderDataMapper.orderToCreateOrderResponse(order, "Order created successfully")).thenReturn(expectedResponse);
        when(orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent)).thenReturn(payload);
        when(orderSagaHelper.orderStatusToSagaStatus(OrderStatus.PENDING)).thenReturn(SagaStatus.STARTED);

        CreateOrderResponse result = handler.createOrder(command);

        assertEquals(OrderStatus.PENDING, result.getOrderStatus());
        assertEquals("Order created successfully", result.getMessage());
        assertNotNull(result.getOrderTrackingId());
    }

    @Test
    void createOrder_callsPersistOrderOnce() {
        when(orderCreateHelper.persistOrder(command)).thenReturn(orderCreatedEvent);
        when(orderDataMapper.orderToCreateOrderResponse(order, "Order created successfully")).thenReturn(expectedResponse);
        when(orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent)).thenReturn(payload);
        when(orderSagaHelper.orderStatusToSagaStatus(OrderStatus.PENDING)).thenReturn(SagaStatus.STARTED);

        handler.createOrder(command);

        verify(orderCreateHelper, times(1)).persistOrder(command);
    }

    @Test
    void createOrder_callsMapperForResponseWithCorrectMessage() {
        when(orderCreateHelper.persistOrder(command)).thenReturn(orderCreatedEvent);
        when(orderDataMapper.orderToCreateOrderResponse(order, "Order created successfully")).thenReturn(expectedResponse);
        when(orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent)).thenReturn(payload);
        when(orderSagaHelper.orderStatusToSagaStatus(OrderStatus.PENDING)).thenReturn(SagaStatus.STARTED);

        handler.createOrder(command);

        verify(orderDataMapper, times(1)).orderToCreateOrderResponse(order, "Order created successfully");
    }

    @Test
    void createOrder_callsMapperForPaymentPayloadWithEvent() {
        when(orderCreateHelper.persistOrder(command)).thenReturn(orderCreatedEvent);
        when(orderDataMapper.orderToCreateOrderResponse(order, "Order created successfully")).thenReturn(expectedResponse);
        when(orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent)).thenReturn(payload);
        when(orderSagaHelper.orderStatusToSagaStatus(OrderStatus.PENDING)).thenReturn(SagaStatus.STARTED);

        handler.createOrder(command);

        verify(orderDataMapper, times(1)).orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent);
    }

    @Test
    void createOrder_callsSavePaymentOutboxMessageWithStartedOutboxStatus() {
        when(orderCreateHelper.persistOrder(command)).thenReturn(orderCreatedEvent);
        when(orderDataMapper.orderToCreateOrderResponse(order, "Order created successfully")).thenReturn(expectedResponse);
        when(orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent)).thenReturn(payload);
        when(orderSagaHelper.orderStatusToSagaStatus(OrderStatus.PENDING)).thenReturn(SagaStatus.STARTED);

        handler.createOrder(command);

        verify(paymentOutboxHelper, times(1)).savePaymentOutboxMessage(
                eq(payload),
                eq(OrderStatus.PENDING),
                eq(SagaStatus.STARTED),
                eq(OutboxStatus.STARTED),
                any(UUID.class));
    }

    @Test
    void createOrder_savePaymentOutboxMessageReceivesNonNullSagaId() {
        when(orderCreateHelper.persistOrder(command)).thenReturn(orderCreatedEvent);
        when(orderDataMapper.orderToCreateOrderResponse(order, "Order created successfully")).thenReturn(expectedResponse);
        when(orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent)).thenReturn(payload);
        when(orderSagaHelper.orderStatusToSagaStatus(OrderStatus.PENDING)).thenReturn(SagaStatus.STARTED);

        ArgumentCaptor<UUID> sagaIdCaptor = ArgumentCaptor.forClass(UUID.class);
        handler.createOrder(command);

        verify(paymentOutboxHelper).savePaymentOutboxMessage(
                any(), any(), any(), any(), sagaIdCaptor.capture());
        assertNotNull(sagaIdCaptor.getValue());
    }

    @Test
    void createOrder_whenPersistOrderThrows_propagatesException() {
        when(orderCreateHelper.persistOrder(command))
                .thenThrow(new OrderDomainException("Could not find customer"));

        assertThrows(OrderDomainException.class, () -> handler.createOrder(command));
    }

    @Test
    void createOrder_whenSavePaymentOutboxMessageThrows_propagatesException() {
        when(orderCreateHelper.persistOrder(command)).thenReturn(orderCreatedEvent);
        when(orderDataMapper.orderToCreateOrderResponse(order, "Order created successfully")).thenReturn(expectedResponse);
        when(orderDataMapper.orderCreatedEventToOrderPaymentEventPayload(orderCreatedEvent)).thenReturn(payload);
        when(orderSagaHelper.orderStatusToSagaStatus(OrderStatus.PENDING)).thenReturn(SagaStatus.STARTED);
        doThrow(new OrderDomainException("Could not save outbox message"))
                .when(paymentOutboxHelper).savePaymentOutboxMessage(any(), any(), any(), any(), any());

        assertThrows(OrderDomainException.class, () -> handler.createOrder(command));
    }
}
